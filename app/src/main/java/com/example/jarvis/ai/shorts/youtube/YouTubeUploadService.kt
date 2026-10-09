package com.example.jarvis.ai.shorts.youtube

import android.content.Context
import android.util.Log
import com.example.jarvis.core.common.AppConfig
import com.example.jarvis.domain.shorts.ShortProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object YouTubeUploadService {

    suspend fun uploadShortToYouTube(
        context: Context,
        project: ShortProject
    ): Result<String> = withContext(Dispatchers.IO) {
        val videoPath = project.videoPath
        if (videoPath.isNullOrBlank() || !File(videoPath).exists()) {
            return@withContext Result.failure(Exception("El archivo de video MP4 no existe o no se ha renderizado."))
        }

        val videoFile = File(videoPath)
        val token = YouTubeAuthManager.getAccessToken(context)

        Log.i("JARVIS", "[YOUTUBE_UPLOAD] Starting upload for ${project.topic} size=${videoFile.length()} bytes")

        try {
            val url = URL("${AppConfig.getBackendUrl()}/api/youtube/upload")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connectTimeout = 5000
                readTimeout = 15000
                doOutput = true
            }

            val title = project.metadata?.title ?: project.topic
            val desc = project.metadata?.description ?: "YouTube Short generado por JARVIS AI."

            val bodyJson = JSONObject().apply {
                put("title", title)
                put("description", desc)
                put("videoPath", videoFile.absolutePath)
                put("privacyStatus", "public")
            }.toString()

            conn.outputStream.use { os ->
                os.write(bodyJson.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            Log.i("JARVIS", "[YOUTUBE_UPLOAD] Upload response HTTP=$code")

            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) {
                val resStr = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonRes = JSONObject(resStr)
                val videoId = jsonRes.optString("videoId", "yt_short_${System.currentTimeMillis()}")
                Log.i("JARVIS", "[YOUTUBE_UPLOAD] Upload successful videoId=$videoId")
                Result.success(videoId)
            } else {
                val errStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e("JARVIS", "[YOUTUBE_UPLOAD] Upload failed HTTP_$code: $errStr")
                Result.failure(Exception("No fue posible subir el video a YouTube ($code): $errStr"))
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "[YOUTUBE_UPLOAD] Exception during upload: ${e.message}", e)
            Result.failure(e)
        }
    }
}
