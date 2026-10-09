package com.example.jarvis.ai.context

class DefaultContextBuilder(
    private val retriever: ContextRetriever,
    private val router: ContextRouter,
    private val ranker: ContextRanker
) : ContextBuilder {
    override suspend fun buildContext(request: ContextRequest): Map<String, Any> {
        val levels = router.route(request)
        val collectedItems = mutableListOf<Map<String, Any>>()
        for (level in levels) {
            val retrieved = retriever.retrieve(level, request.query)
            collectedItems.add(retrieved)
        }
        val ranked = ranker.rank(collectedItems)
        return ranked.firstOrNull() ?: emptyMap()
    }
}
