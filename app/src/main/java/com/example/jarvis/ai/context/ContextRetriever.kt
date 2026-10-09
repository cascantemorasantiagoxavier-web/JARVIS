package com.example.jarvis.ai.context

interface ContextRetriever {
    suspend fun retrieve(level: ContextLevel, query: String): Map<String, Any>
}
