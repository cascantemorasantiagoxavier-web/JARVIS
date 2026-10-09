package com.example.jarvis.ai.context

class DefaultContextRetriever : ContextRetriever {
    override suspend fun retrieve(level: ContextLevel, query: String): Map<String, Any> {
        return mapOf(
            "level" to level.name,
            "query" to query,
            "data" to "Retrieved context for level ${level.name}"
        )
    }
}
