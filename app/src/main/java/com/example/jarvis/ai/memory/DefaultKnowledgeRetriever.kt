package com.example.jarvis.ai.memory

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.KnowledgeRetriever
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryResult
import com.example.jarvis.obsidian.search.VaultSearcher

/**
 * Default implementation of KnowledgeRetriever using VaultSearcher for selective snippet extraction.
 */
class DefaultKnowledgeRetriever(
    private val searcher: VaultSearcher
) : KnowledgeRetriever {

    override suspend fun retrieveKnowledge(query: String, maxSnippets: Int): AppResult<MemoryResult> {
        if (query.isBlank()) {
            return AppResult.Success(MemoryResult(query = query))
        }

        return try {
            val snippets = searcher.searchSnippets(query, maxSnippets)
            val entries = snippets.map { snippet ->
                MemoryEntry(
                    content = "[${snippet.filePath}:${snippet.lineNumber}] ${snippet.lineContent}",
                    level = MemoryLevel.KNOWLEDGE,
                    source = "obsidian:${snippet.filePath}",
                    importance = 0.8f,
                    tags = listOf("knowledge", "obsidian")
                )
            }

            AppResult.Success(
                MemoryResult(
                    query = query,
                    entries = entries,
                    snippets = snippets.map { it.lineContent },
                    totalCount = entries.size,
                    relevanceScore = if (entries.isNotEmpty()) 0.9f else 0.0f
                )
            )
        } catch (e: Exception) {
            AppResult.Error(e, "Failed to retrieve knowledge snippets")
        }
    }
}
