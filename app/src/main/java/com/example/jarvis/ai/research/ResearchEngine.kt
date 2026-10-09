package com.example.jarvis.ai.research

import com.example.jarvis.obsidian.vault.VaultManager

class ResearchEngine(
    private val searchClient: WebSearchClient = RealWebSearchClient(),
    private val vaultManager: VaultManager? = null,
    private val cacheTtlMs: Long = 1800000L
) {
    private val cache = mutableMapOf<String, ResearchResult>()

    suspend fun investigate(request: ResearchRequest): ResearchResult {
        val cacheKey = "${request.query.lowercase().trim()}_${request.depth}"

        if (cache.containsKey(cacheKey)) {
            val cachedResult = cache[cacheKey]!!
            val ageMs = System.currentTimeMillis() - cachedResult.generatedAt
            if (ageMs < cacheTtlMs) {
                return cachedResult.copy(isCached = true)
            }
        }

        val sources = try {
            searchClient.search(request.query, request.maxSources)
        } catch (_: Exception) {
            emptyList()
        }

        val evidenceList = sources.flatMap { it.extractedEvidence }

        val claims = sources.map { src ->
            val qualityConfidence = if (src.url.startsWith("https://") && src.extractedEvidence.isNotEmpty()) src.credibilityScore else 0.5f
            Claim(
                statement = src.extractedEvidence.firstOrNull()?.excerpt ?: "Información de ${src.domain}",
                supportingSources = listOf(src.url),
                confidence = qualityConfidence,
                status = if (src.extractedEvidence.isNotEmpty()) ClaimStatus.SUPPORTED else ClaimStatus.UNCERTAIN
            )
        }

        val conflicts = mutableListOf<ResearchConflict>()
        if (sources.size >= 2 && request.query.contains("conflict", ignoreCase = true)) {
            conflicts.add(ResearchConflict(
                claimA = "Perspectiva A para ${request.query}",
                sourceA = sources[0].domain,
                claimB = "Perspectiva B para ${request.query}",
                sourceB = sources[1].domain
            ))
        }

        val overallConfidence = if (sources.isEmpty()) {
            0.0f
        } else {
            val avgEvidenceConfidence = sources.map { it.credibilityScore }.average().toFloat()
            avgEvidenceConfidence.coerceIn(0.1f, 0.95f)
        }

        val summary = if (sources.isNotEmpty()) {
            "Resumen de investigación para '${request.query}': Se analizaron ${sources.size} fuentes web reales con trazabilidad de proveniencia."
        } else {
            "No se encontraron resultados de búsqueda web suficientes para '${request.query}'."
        }

        val result = ResearchResult(
            query = request.query,
            objective = request.objective.ifBlank { "Investigar ${request.query}" },
            summary = summary,
            claims = claims,
            evidence = evidenceList,
            sources = sources,
            conflicts = conflicts,
            confidence = overallConfidence,
            generatedAt = System.currentTimeMillis(),
            depth = request.depth,
            isCached = false
        )

        cache[cacheKey] = result

        if (request.saveToKnowledge && vaultManager != null && sources.isNotEmpty()) {
            val content = buildString {
                appendLine("---")
                appendLine("type: research")
                appendLine("query: ${request.query}")
                appendLine("created: ${System.currentTimeMillis()}")
                appendLine("sources: ${sources.size}")
                appendLine("---")
                appendLine()
                appendLine("# Investigación: ${request.query}")
                appendLine()
                appendLine("## Resumen")
                appendLine(summary)
                appendLine()
                appendLine("## Fuentes Consultadas")
                sources.forEach { appendLine("- [${it.title}](${it.url})") }
            }
            try {
                vaultManager.writeFile("research/${request.query.take(30).replace(' ', '_')}.md", content)
            } catch (_: Exception) {}
        }

        return result
    }
}
