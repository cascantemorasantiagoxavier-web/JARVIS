package com.example.jarvis.ai.research

import com.example.jarvis.core.common.AppConfig
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RealWebSearchClient(
    private val backendUrl: String = AppConfig.getBackendUrl(),
    private val logger: AppLogger = ConsoleLogger(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
) : WebSearchClient {

    override suspend fun search(query: String, maxSources: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        logger.i("RealWebSearchClient", "Performing real web search for query: '$query'")

        // 1. First try secure backend search endpoint (if available)
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$backendUrl/api/search?q=$encodedQuery&limit=$maxSources")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "[]"
                    val sources = parseBackendSearchResponse(bodyStr, query)
                    if (sources.isNotEmpty()) {
                        return@withContext sources.take(maxSources)
                    }
                }
            }
        } catch (e: Exception) {
            logger.i("RealWebSearchClient", "Backend search endpoint unavailable/fallback to DuckDuckGo API: ${e.message}")
        }

        // 2. Direct Web Search via DuckDuckGo Instant Answer API
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1")
                .addHeader("User-Agent", "JARVIS-Android-Assistant/1.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "{}"
                    val sources = parseDuckDuckGoResponse(bodyStr, query)
                    if (sources.isNotEmpty()) {
                        return@withContext sources.take(maxSources)
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("RealWebSearchClient", "Error fetching search results: ${e.message}")
            throw IOException("SIN_INTERNET: Se requiere conexión a Internet para realizar búsquedas web.")
        }

        // 3. HTML parsing fallback if API results empty
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encodedQuery")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val htmlStr = response.body?.string() ?: ""
                    val sources = parseDuckDuckGoHtml(htmlStr, query)
                    if (sources.isNotEmpty()) {
                        return@withContext sources.take(maxSources)
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("RealWebSearchClient", "HTML Search fallback error: ${e.message}")
        }

        emptyList()
    }

    private fun parseBackendSearchResponse(jsonStr: String, query: String): List<ResearchSource> {
        val list = mutableListOf<ResearchSource>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url", "")
                val title = obj.optString("title", query)
                val snippet = obj.optString("snippet", obj.optString("content", ""))
                val domain = try { URL(url).host } catch (_: Exception) { "web.search" }

                if (url.isNotBlank()) {
                    list.add(
                        ResearchSource(
                            url = url,
                            title = title,
                            domain = domain,
                            sourceType = if (url.contains("news") || query.contains("noticias")) SourceType.NEWS else SourceType.OFFICIAL,
                            credibilityScore = 0.9f,
                            relevance = 0.95f,
                            extractedEvidence = listOf(
                                Evidence(
                                    sourceId = "src_$i",
                                    claim = title,
                                    excerpt = snippet.ifBlank { "Resultado web para $query" },
                                    confidence = 0.9f
                                )
                            )
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun parseDuckDuckGoResponse(jsonStr: String, query: String): List<ResearchSource> {
        val list = mutableListOf<ResearchSource>()
        try {
            val json = JSONObject(jsonStr)
            val abstractUrl = json.optString("AbstractURL", "")
            val abstractText = json.optString("AbstractText", "")
            val heading = json.optString("Heading", query)

            if (abstractUrl.isNotBlank() && abstractText.isNotBlank()) {
                val domain = try { URL(abstractUrl).host } catch (_: Exception) { "duckduckgo.com" }
                list.add(
                    ResearchSource(
                        url = abstractUrl,
                        title = heading,
                        domain = domain,
                        sourceType = SourceType.OFFICIAL,
                        credibilityScore = 0.92f,
                        relevance = 0.95f,
                        extractedEvidence = listOf(
                            Evidence(
                                sourceId = "ddg_main",
                                claim = heading,
                                excerpt = abstractText,
                                confidence = 0.92f
                            )
                        )
                    )
                )
            }

            val relatedTopics = json.optJSONArray("RelatedTopics") ?: JSONArray()
            for (i in 0 until relatedTopics.length()) {
                val topic = relatedTopics.optJSONObject(i) ?: continue
                val firstUrl = topic.optString("FirstURL", "")
                val text = topic.optString("Text", "")
                if (firstUrl.isNotBlank() && text.isNotBlank()) {
                    val domain = try { URL(firstUrl).host } catch (_: Exception) { "duckduckgo.com" }
                    val title = text.take(60)
                    list.add(
                        ResearchSource(
                            url = firstUrl,
                            title = title,
                            domain = domain,
                            sourceType = SourceType.OTHER,
                            credibilityScore = 0.85f,
                            relevance = 0.88f,
                            extractedEvidence = listOf(
                                Evidence(
                                    sourceId = "ddg_$i",
                                    claim = title,
                                    excerpt = text,
                                    confidence = 0.85f
                                )
                            )
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun parseDuckDuckGoHtml(htmlStr: String, query: String): List<ResearchSource> {
        val list = mutableListOf<ResearchSource>()
        try {
            val regex = """<a class="result__url" href="([^"]+)".*?>\s*(.*?)\s*</a>""".toRegex()
            val matches = regex.findAll(htmlStr)
            var index = 0
            for (match in matches) {
                val rawUrl = match.groupValues[1].trim()
                val domain = match.groupValues[2].trim().replace("&nbsp;", "")
                val cleanUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else if (!rawUrl.startsWith("http")) "https://$rawUrl" else rawUrl

                if (cleanUrl.isNotBlank() && !cleanUrl.contains("duckduckgo.com")) {
                    list.add(
                        ResearchSource(
                            url = cleanUrl,
                            title = "Resultado web: $domain para $query",
                            domain = domain.ifBlank { "web" },
                            sourceType = SourceType.NEWS,
                            credibilityScore = 0.88f,
                            relevance = 0.9f,
                            extractedEvidence = listOf(
                                Evidence(
                                    sourceId = "html_$index",
                                    claim = "Búsqueda web sobre $query",
                                    excerpt = "Fuente encontrada en $domain",
                                    confidence = 0.88f
                                )
                            )
                        )
                    )
                    index++
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
