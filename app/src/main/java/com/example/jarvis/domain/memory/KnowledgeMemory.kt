package com.example.jarvis.domain.memory

interface KnowledgeMemory {
    suspend fun queryKnowledge(query: String): List<String>
}
