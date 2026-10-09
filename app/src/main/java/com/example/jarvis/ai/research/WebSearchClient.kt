package com.example.jarvis.ai.research

interface WebSearchClient {
    suspend fun search(query: String, maxSources: Int): List<ResearchSource>
}

class MockWebSearchClient : WebSearchClient {
    override suspend fun search(query: String, maxSources: Int): List<ResearchSource> {
        val domain = if (query.contains("android", ignoreCase = true)) "developer.android.com" else "github.com"
        val sources = listOf(
            ResearchSource(
                url = "https://$domain/docs/search?q=$query",
                title = "Documentation for $query",
                domain = domain,
                sourceType = SourceType.DOCUMENTATION,
                credibilityScore = 0.95f,
                relevance = 0.9f,
                extractedEvidence = listOf(
                    Evidence(sourceId = "src_1", claim = "Primary finding regarding $query", excerpt = "Evidence snippet for $query", confidence = 0.9f)
                )
            ),
            ResearchSource(
                url = "https://community.dev/posts/$query",
                title = "Community Discussion on $query",
                domain = "community.dev",
                sourceType = SourceType.COMMUNITY,
                credibilityScore = 0.7f,
                relevance = 0.75f,
                extractedEvidence = listOf(
                    Evidence(sourceId = "src_2", claim = "Alternative perspective on $query", excerpt = "Community note on $query", confidence = 0.7f)
                )
            )
        )
        return sources.take(maxSources)
    }
}
