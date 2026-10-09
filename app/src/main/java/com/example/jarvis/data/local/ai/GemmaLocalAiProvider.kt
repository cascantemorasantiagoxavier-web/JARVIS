package com.example.jarvis.data.local.ai

import android.content.Context
import android.os.Environment
import com.example.jarvis.data.remote.ai.ChatMessagePayload
import com.example.jarvis.domain.ai.AiProvider
import com.example.jarvis.domain.ai.AiSession
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Provider local de Inteligencia Artificial utilizando Gemma (Gemma-2B / Gemma-2 2B) en Android.
 * Integra ejecuciones locales en dispositivo vía MediaPipe LLM Inference API y motor local alternativo HTTP.
 */
class GemmaLocalAiProvider(
    context: Context? = null,
    private val localModelPath: String = try {
        File(Environment.getExternalStorageDirectory(), "Download/gemma-2b-it-gpu-int4.bin").absolutePath
    } catch (_: Throwable) {
        "/tmp/gemma-2b-it-gpu-int4.bin"
    },
    private val localServerUrl: String = "http://127.0.0.1:11434"
) : AiProvider {

    private var appContext: Context? = context?.applicationContext
    private var currentSession: AiSession? = null
    private var llmInference: LlmInference? = null
    private var isLlmInitialized = false

    fun setApplicationContext(context: Context) {
        this.appContext = context.applicationContext
    }

    fun isModelAvailable(): Boolean {
        return try {
            val modelFile = File(localModelPath)
            modelFile.exists() && modelFile.canRead()
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun createSession(): Result<AiSession> = withContext(Dispatchers.IO) {
        val session = AiSession(
            sessionId = "gemma_session_${UUID.randomUUID()}",
            isActive = true
        )
        currentSession = session
        Result.success(session)
    }

    override suspend fun sendText(session: AiSession, text: String): Result<Unit> {
        val result = generateResponse(
            messages = listOf(ChatMessagePayload(role = "user", content = text))
        )
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Error en Gemma local"))
        }
    }

    override suspend fun disconnect(session: AiSession) {
        if (currentSession?.sessionId == session.sessionId) {
            currentSession = null
        }
    }

    /**
     * Genera respuesta localmente utilizando Gemma.
     * Si el modelo no existe físicamente, retorna un Result.failure con GEMMA_NOT_AVAILABLE.
     */
    suspend fun generateResponse(
        messages: List<ChatMessagePayload>,
        systemPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        // Opción A: Intentar ejecución local mediante MediaPipe LlmInference si el archivo del modelo existe
        val activeContext = appContext
        if (activeContext != null) {
            val modelFile = File(localModelPath)
            if (modelFile.exists() && modelFile.canRead()) {
                try {
                    if (!isLlmInitialized || llmInference == null) {
                        val options = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(localModelPath)
                            .setMaxTokens(2048)
                            .build()
                        llmInference = LlmInference.createFromOptions(activeContext, options)
                        isLlmInitialized = true
                    }

                    val fullPrompt = buildPromptString(messages, systemPrompt)
                    val responseText = llmInference?.generateResponse(fullPrompt)
                    if (!responseText.isNullOrBlank()) {
                        return@withContext Result.success(responseText.trim())
                    }
                } catch (_: Exception) {
                    // Fallthrough to local server or structured failure
                }
            }
        }

        // Opción B: Intentar motor local HTTP (ej. Ollama / LM Studio en red local)
        try {
            val url = URL("$localServerUrl/api/generate")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3000
                readTimeout = 8000
                doOutput = true
            }

            val promptText = buildPromptString(messages, systemPrompt)
            val jsonPayload = JSONObject().apply {
                put("model", "gemma:2b")
                put("prompt", promptText)
                put("stream", false)
            }

            connection.outputStream.use { os ->
                os.write(jsonPayload.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val resStr = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonRes = JSONObject(resStr)
                val responseContent = jsonRes.optString("response", "")
                if (responseContent.isNotBlank()) {
                    return@withContext Result.success(responseContent.trim())
                }
            }
        } catch (_: Exception) {
            // Server not available
        }

        // Retorno honesto de no disponibilidad cuando no existe el modelo ni el motor local
        Result.failure(
            Exception("GEMMA_NOT_AVAILABLE: El modelo 'gemma-2b-it-gpu-int4.bin' no se encuentra en el almacenamiento local y el motor local no está activo.")
        )
    }

    private fun buildPromptString(messages: List<ChatMessagePayload>, systemPrompt: String?): String {
        val sb = StringBuilder()
        if (!systemPrompt.isNullOrBlank()) {
            sb.append("<start_of_turn>system\n").append(systemPrompt).append("<end_of_turn>\n")
        }
        for (msg in messages) {
            val roleTag = if (msg.role == "user") "user" else "model"
            sb.append("<start_of_turn>").append(roleTag).append("\n")
                .append(msg.content).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
