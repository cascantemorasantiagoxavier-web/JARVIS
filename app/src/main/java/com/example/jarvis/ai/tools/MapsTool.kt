package com.example.jarvis.ai.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import java.net.URLEncoder

class MapsTool(
    private val contextProvider: () -> Context? = { null }
) : Tool {

    override val name = "maps_tool"
    override val description = "Busca lugares, calcula rutas o inicia navegación en Google Maps"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val action = params["action"]?.toString()?.lowercase() ?: "search"
        val query = params["query"]?.toString() ?: params["destination"]?.toString() ?: params["place"]?.toString() ?: ""
        val origin = params["origin"]?.toString() ?: ""
        val mode = params["mode"]?.toString() ?: "driving"

        val targetQuery = query.ifBlank { "Costa Rica" }
        val encodedQuery = URLEncoder.encode(targetQuery, "UTF-8")
        val url = when (action) {
            "directions", "ruta" -> {
                val encodedOrigin = if (origin.isNotBlank()) URLEncoder.encode(origin, "UTF-8") else ""
                "https://www.google.com/maps/dir/?api=1&destination=$encodedQuery&origin=$encodedOrigin&travelmode=$mode"
            }
            "navigation", "navegacion" -> {
                "google.navigation:q=$encodedQuery"
            }
            "search", "buscar" -> {
                "https://www.google.com/maps/search/?api=1&query=$encodedQuery"
            }
            else -> {
                if (query.isBlank()) {
                    "https://www.google.com/maps"
                } else {
                    "https://www.google.com/maps/search/?api=1&query=$encodedQuery"
                }
            }
        }

        val ctx = contextProvider()
        if (ctx != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (url.startsWith("google.navigation")) {
                        setPackage("com.google.android.apps.maps")
                    }
                }
                ctx.startActivity(intent)
                return "Abriendo Google Maps para: $targetQuery"
            } catch (e: Exception) {
                val webFallback = "https://www.google.com/maps/search/?api=1&query=$encodedQuery"
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webFallback)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(fallbackIntent)
                    return "Abriendo Google Maps en navegador para: $targetQuery"
                } catch (ex: Exception) {
                    return "No pude abrir Google Maps: ${ex.message}"
                }
            }
        }

        return "Maps URL generada: $url"
    }
}
