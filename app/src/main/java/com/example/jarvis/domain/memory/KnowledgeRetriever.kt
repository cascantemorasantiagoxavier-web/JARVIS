package com.example.jarvis.domain.memory

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.models.MemoryResult

/**
 * Interface for selective knowledge retrieval from documentation sources (e.g., Obsidian Vault)
 * without loading entire vaults into memory.
 */
interface KnowledgeRetriever {
    suspend fun retrieveKnowledge(query: String, maxSnippets: Int = 5): AppResult<MemoryResult>
}
