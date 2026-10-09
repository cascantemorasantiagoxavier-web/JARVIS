package com.example.jarvis.ai.tools

import com.example.jarvis.core.common.AppConfig
import com.example.jarvis.core.util.JsonUtils
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import java.net.HttpURLConnection
import java.net.URL

class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Busca información actual y noticias en Internet en tiempo real"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: params["topic"]?.toString() ?: return "Consulta de búsqueda vacía."
        try {
            println("[WEB] SEARCH_STARTED")
            println("[WEB] SEARCH_QUERY=$query")
            val url = URL("${AppConfig.getBackendUrl()}/api/web/search")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3000
                readTimeout = 6000
                doOutput = true
            }

            val bodyStr = "{\"query\":\"${JsonUtils.escape(query)}\",\"limit\":5}"
            conn.outputStream.use { it.write(bodyStr.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            println("[WEB] SEARCH_HTTP_STATUS=$responseCode")
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val resStr = conn.inputStream.bufferedReader().use { it.readText() }
                println("[WEB] SEARCH_RESULTS response received length=${resStr.length}")
                return "Resultados web para '$query':\n$resStr"
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                println("[WEB] SEARCH_ERROR HTTP_$responseCode: $err")
                return "No tengo conexión a Internet en este momento, jefe."
            }
        } catch (e: Exception) {
            println("[WEB] SEARCH_ERROR: ${e.message}")
            return "No tengo conexión a Internet en este momento, jefe."
        }
    }
}

class OpenWebPageTool : Tool {
    override val name = "open_web_page"
    override val description = "Abre y extrae el contenido de una URL web específica"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val urlParam = params["url"]?.toString() ?: params["query"]?.toString() ?: return "URL no especificada."
        try {
            println("[WEB] PAGE_OPENED url=$urlParam")
            val url = URL("${AppConfig.getBackendUrl()}/api/web/extract")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3000
                readTimeout = 6000
                doOutput = true
            }

            val bodyStr = "{\"url\":\"${JsonUtils.escape(urlParam)}\"}"
            conn.outputStream.use { it.write(bodyStr.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val resStr = conn.inputStream.bufferedReader().use { it.readText() }
                println("[WEB] CONTENT_EXTRACTED response received length=${resStr.length}")
                return "Contenido extraído de '$urlParam':\n$resStr"
            } else {
                return "No tengo conexión a Internet en este momento, jefe."
            }
        } catch (e: Exception) {
            println("[WEB] SEARCH_ERROR: ${e.message}")
            return "No tengo conexión a Internet en este momento, jefe."
        }
    }
}
