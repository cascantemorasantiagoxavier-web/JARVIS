package com.example.jarvis.ai.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class OpenWebTool(
    private val contextProvider: () -> Context? = { null }
) : Tool {

    override val name = "open_web"
    override val description = "Abre una URL web de forma segura en el navegador"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val url = params["url"]?.toString() ?: params["query"]?.toString() ?: ""

        if (url.isBlank()) {
            return "Por favor, especifica una URL válida para abrir."
        }

        val lowerUrl = url.lowercase().trim()
        if (lowerUrl.startsWith("javascript:") || lowerUrl.startsWith("file:") ||
            lowerUrl.startsWith("content:") || lowerUrl.startsWith("intent:")) {
            return "URL no permitida por razones de seguridad."
        }

        val finalUrl = if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        val ctx = contextProvider()
        if (ctx != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return "Abriendo página web: $finalUrl"
            } catch (e: Exception) {
                return "No pude abrir la página web: ${e.message}"
            }
        }

        return "URL validada para abrir: $finalUrl"
    }
}
