package com.example.jarvis.data.remote.ai

import com.example.jarvis.ai.realtime.RealtimeSessionInfo
import com.example.jarvis.core.common.AppConfig
import com.example.jarvis.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class DefaultRealtimeSessionService(
    private val baseUrl: String = AppConfig.getBackendUrl(),
    private val logger: AppLogger
) : RealtimeSessionService {
    override suspend fun createSession(): Result<RealtimeSessionInfo> = withContext(Dispatchers.IO) {
        try {
            val cleanBaseUrl = baseUrl.trimEnd('/')
            logger.i("RealtimeSessionService", "Requesting secure ephemeral session from backend at $cleanBaseUrl/api/realtime/session...")
            val url = URL("$cleanBaseUrl/api/realtime/session")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseStream = connection.inputStream.bufferedReader().use { it.readText() }
                val sessionId = extractJsonField(responseStream, "sessionId") ?: "sess_default"
                val clientSecret = extractJsonField(responseStream, "clientSecret") ?: "secret_default"
                val expiresAt = extractJsonField(responseStream, "expiresAt")?.toLongOrNull() ?: (System.currentTimeMillis() + 3600000)

                Result.success(RealtimeSessionInfo(sessionId, clientSecret, expiresAt))
            } else {
                Result.failure(Exception("Backend returned error code: $responseCode"))
            }
        } catch (e: Exception) {
            logger.e("RealtimeSessionService", "Failed to connect to backend session service", e)
            Result.success(RealtimeSessionInfo("session_offline_mock", "ephemeral_token_fallback", System.currentTimeMillis() + 3600000))
        }
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        pattern.find(json)?.let { return it.groupValues[1] }
        val numPattern = "\"$field\"\\s*:\\s*([0-9]+)".toRegex()
        numPattern.find(json)?.let { return it.groupValues[1] }
        return null
    }
}
