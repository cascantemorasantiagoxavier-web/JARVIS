package com.example.jarvis.ai.memory

import com.example.jarvis.domain.memory.KnowledgeMemory
import com.example.jarvis.domain.memory.LongTermMemory
import com.example.jarvis.domain.memory.SessionMemory
import com.example.jarvis.domain.memory.WorkingMemory

class DefaultAiMemoryManager : AiMemoryManager, WorkingMemory, SessionMemory, LongTermMemory, KnowledgeMemory {
    private val workingContext = mutableMapOf<String, Any>()
    private val sessionHistory = mutableListOf<String>()
    private val longTermStore = mutableMapOf<String, String>()
    private val knowledgeBase = mutableMapOf<String, String>()

    override suspend fun recall(query: String): String {
        return longTermStore[query] ?: knowledgeBase[query] ?: "No memory found for: $query"
    }

    override suspend fun memorize(key: String, value: String) {
        longTermStore[key] = value
    }

    override fun getActiveContext(): Map<String, Any> {
        return workingContext
    }

    override fun updateContext(key: String, value: Any) {
        workingContext[key] = value
    }

    override fun getSessionHistory(): List<String> {
        return sessionHistory
    }

    override fun addSessionEntry(entry: String) {
        sessionHistory.add(entry)
    }

    override suspend fun store(key: String, value: String) {
        longTermStore[key] = value
    }

    override suspend fun retrieve(key: String): String? {
        return longTermStore[key]
    }

    override suspend fun queryKnowledge(query: String): List<String> {
        return knowledgeBase.entries
            .filter { it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
            .map { "${it.key}: ${it.value}" }
    }
}
