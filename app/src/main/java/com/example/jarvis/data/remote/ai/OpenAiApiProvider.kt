package com.example.jarvis.data.remote.ai

import android.util.Log
import com.example.jarvis.core.common.AppConfig
import com.example.jarvis.domain.ai.AiProvider
import com.example.jarvis.domain.ai.AiSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ChatMessagePayload(
    val role: String,
    val content: String
)

class OpenAiApiProvider : AiProvider {

    private val baseUrl: String
        get() = AppConfig.getBackendUrl()

    private var currentSession: AiSession? = null

    override suspend fun createSession(): Result<AiSession> = withContext(Dispatchers.IO) {
        val session = AiSession(
            sessionId = "session_${UUID.randomUUID()}",
            isActive = true
        )
        currentSession = session
        Result.success(session)
    }

    override suspend fun sendText(session: AiSession, text: String): Result<Unit> {
        val chatResult = generateResponse(
            messages = listOf(ChatMessagePayload(role = "user", content = text))
        )
        return if (chatResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(chatResult.exceptionOrNull() ?: Exception("OpenAI communication error"))
        }
    }

    override suspend fun disconnect(session: AiSession) {
        if (currentSession?.sessionId == session.sessionId) {
            currentSession = null
        }
    }

    suspend fun generateResponse(
        messages: List<ChatMessagePayload>,
        systemPrompt: String? = null,
        model: String? = null,
        maxRetries: Int = 2
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        Log.i("JARVIS", "[JARVIS][OPENAI] REQUEST_STARTED endpoint=$cleanBaseUrl/api/chat")
        var lastError: Exception? = null

        for (attempt in 0..maxRetries) {
            val currentAttempt = attempt + 1
            Log.i("JARVIS", "[JARVIS][OPENAI] ATTEMPT=$currentAttempt")
            try {
                val url = URL("$cleanBaseUrl/api/chat")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 25000
                    doOutput = true
                }

                val payloadJson = JSONObject()
                if (!model.isNullOrBlank()) {
                    payloadJson.put("model", model)
                }

                val messagesArray = JSONArray()
                if (!systemPrompt.isNullOrBlank()) {
                    val sysObj = JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    messagesArray.put(sysObj)
                }

                for (msg in messages) {
                    val msgObj = JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                    messagesArray.put(msgObj)
                }
                payloadJson.put("messages", messagesArray)

                val bodyBytes = payloadJson.toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { os ->
                    os.write(bodyBytes)
                    os.flush()
                }

                val responseCode = connection.responseCode
                Log.i("JARVIS", "[JARVIS][OPENAI] HTTP_STATUS=$responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonRes = JSONObject(responseStr)
                    val content = jsonRes.optString("content", "")
                    if (content.isNotBlank()) {
                        Log.i("JARVIS", "[JARVIS][OPENAI] RESPONSE_RECEIVED=true length=${content.length}")
                        return@withContext Result.success(content)
                    } else {
                        return@withContext Result.failure(Exception("No tengo conexión a Internet en este momento, jefe."))
                    }
                } else {
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.e("JARVIS", "[JARVIS][OPENAI] HTTP_ERROR=$responseCode message=$errorStream")

                    val isTemporary = responseCode == 503 || responseCode == 429
                    if (isTemporary && attempt < maxRetries) {
                        val backoffMs = 1500L * (attempt + 1)
                        delay(backoffMs)
                        continue
                    }

                    val formattedMsg = when (responseCode) {
                        404 -> "EL GATEWAY DE PRODUCCIÓN NO ESTÁ DISPONIBLE / NO ESTÁ DESPLEGADO CORRECTAMENTE (HTTP 404 en $cleanBaseUrl/api/chat). Por favor, verifica la URL en Ajustes o despliega el backend."
                        429 -> "El servicio de OpenAI reporta alta demanda temporal."
                        503 -> "El servicio de OpenAI no está disponible temporalmente."
                        401, 403 -> "Error de autenticación con el servicio de IA."
                        else -> "No tengo conexión a Internet en este momento, jefe."
                    }
                    return@withContext Result.failure(Exception(formattedMsg))
                }
            } catch (e: Exception) {
                lastError = e
                val msg = e.message ?: ""
                val isTimeout = msg.contains("timed out", true) || msg.contains("timeout", true)
                val isDNS = msg.contains("Unable to resolve host", true) || msg.contains("No address associated", true)
                val isConnect = msg.contains("Connection refused", true) || msg.contains("failed to connect", true)

                Log.e("JARVIS", "[JARVIS][OPENAI] EXCEPTION class=${e.javaClass.simpleName} message=$msg", e)

                if ((isTimeout || isConnect || isDNS) && attempt < maxRetries) {
                    val backoffMs = 2000L * (attempt + 1)
                    delay(backoffMs)
                    continue
                }
                if (attempt == maxRetries) break
            }
        }

        Log.e("JARVIS", "[JARVIS][OPENAI] FINAL_STATUS=UNAVAILABLE lastError=${lastError?.message}")
        Result.failure(Exception("No tengo conexión a Internet en este momento, jefe."))
    }
}
