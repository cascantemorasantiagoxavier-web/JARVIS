package com.example.jarvis.data.remote.ai

import android.util.Log
import com.example.jarvis.core.common.AppConfig
import com.example.jarvis.core.util.JsonUtils
import com.example.jarvis.domain.ai.AiProvider
import com.example.jarvis.domain.ai.AiSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class GeminiApiProvider : AiProvider {

    private val baseUrl: String
        get() = AppConfig.getBackendUrl()

    private var currentSession: AiSession? = null

    override suspend fun createSession(): Result<AiSession> = withContext(Dispatchers.IO) {
        val session = AiSession(
            sessionId = "gemini_session_${UUID.randomUUID()}",
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
            Result.failure(result.exceptionOrNull() ?: Exception("Gemini communication error"))
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
        model: String? = "gemini-flash-lite-latest",
        maxRetries: Int = 2
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetModel = model ?: "gemini-flash-lite-latest"
        val cleanBaseUrl = baseUrl.trimEnd('/')
        Log.i("JARVIS", "[JARVIS][GEMINI] REQUEST_STARTED model=$targetModel endpoint=$cleanBaseUrl/api/gemini/chat")

        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            val currentAttempt = attempt + 1
            Log.i("JARVIS", "[JARVIS][GEMINI] ATTEMPT=$currentAttempt")
            try {
                val url = URL("$cleanBaseUrl/api/gemini/chat")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 25000
                    doOutput = true
                }

                val sysPart = if (!systemPrompt.isNullOrBlank()) {
                    "{\"role\":\"system\",\"content\":\"${JsonUtils.escape(systemPrompt)}\"},"
                } else ""

                val msgsPart = messages.joinToString(",") { msg ->
                    "{\"role\":\"${JsonUtils.escape(msg.role)}\",\"content\":\"${JsonUtils.escape(msg.content)}\"}"
                }

                val bodyJson = "{\"model\":\"${JsonUtils.escape(targetModel)}\",\"messages\":[$sysPart$msgsPart]}"
                val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)

                connection.outputStream.use { os ->
                    os.write(bodyBytes)
                    os.flush()
                }

                val responseCode = connection.responseCode
                Log.i("JARVIS", "[JARVIS][GEMINI] HTTP_STATUS=$responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObj = try { JSONObject(responseStr) } catch (_: Exception) { null }
                    val content = jsonObj?.optString("content", "")?.ifBlank {
                        val contentMatch = "\"content\"\\s*:\\s*\"([^\"]*)\"".toRegex().find(responseStr)
                        contentMatch?.groupValues?.get(1)?.let { JsonUtils.unescape(it) } ?: ""
                    } ?: ""

                    Log.i("JARVIS", "[JARVIS][GEMINI] RESPONSE_RECEIVED=true length=${content.length}")

                    if (content.isNotBlank()) {
                        return@withContext Result.success(content)
                    } else {
                        return@withContext Result.failure(Exception("No tengo conexión a Internet en este momento, jefe."))
                    }
                } else {
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.e("JARVIS", "[JARVIS][GEMINI] HTTP_ERROR=$responseCode message=$errorStream")

                    val isTemporary = responseCode == 503 || responseCode == 429 || errorStream.contains("high demand", true)
                    if (isTemporary && attempt < maxRetries) {
                        val backoffMs = 1500L * (attempt + 1)
                        Log.w("JARVIS", "[JARVIS][GEMINI] RETRY_REASON=UNAVAILABLE (HTTP $responseCode) backoff=${backoffMs}ms")
                        delay(backoffMs)
                        continue
                    }

                    val formattedMsg = when {
                        errorStream.contains("GEMINI_API_KEY", true) || (responseCode == 500 && errorStream.contains("SERVER_CONFIG_ERROR", true)) ->
                            "Configuración pendiente: Agrega la variable GEMINI_API_KEY en el panel Environment Variables de Render.com."
                        responseCode == 404 -> "EL GATEWAY DE PRODUCCIÓN NO ESTÁ DISPONIBLE / NO ESTÁ DESPLEGADO CORRECTAMENTE (HTTP 404 en $cleanBaseUrl/api/gemini/chat)."
                        responseCode == 429 -> "El servicio de Google Gemini reporta alta demanda temporal."
                        responseCode == 503 -> "Google Gemini está experimentando alta demanda. Inténtalo de nuevo."
                        responseCode == 401 || responseCode == 403 -> "Error de autenticación con el servicio de IA."
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

                Log.e("JARVIS", "[JARVIS][GEMINI] EXCEPTION class=${e.javaClass.simpleName} message=$msg", e)

                if ((isTimeout || isConnect || isDNS) && attempt < maxRetries) {
                    val backoffMs = 2000L * (attempt + 1)
                    Log.w("JARVIS", "[JARVIS][GEMINI] RETRY_REASON=NETWORK_FAILURE backoff=${backoffMs}ms")
                    delay(backoffMs)
                    continue
                }
                if (attempt == maxRetries) break
            }
        }

        Log.e("JARVIS", "[JARVIS][GEMINI] FINAL_STATUS=UNAVAILABLE lastError=${lastError?.message}")
        Result.failure(Exception("No tengo conexión a Internet en este momento, jefe."))
    }
}
