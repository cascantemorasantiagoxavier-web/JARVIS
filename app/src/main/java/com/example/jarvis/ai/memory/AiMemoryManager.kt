package com.example.jarvis.ai.memory

interface AiMemoryManager {
    suspend fun recall(query: String): String
    suspend fun memorize(key: String, value: String)
}
