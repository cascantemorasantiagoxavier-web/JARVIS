package com.example.jarvis.ai.memory

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.MemoryRepository
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryQuery
import com.example.jarvis.domain.memory.models.MemoryResult

/**
 * In-memory local implementation of MemoryRepository.
 * Fully decoupled from documentary Vault/Obsidian storage.
 */
class DefaultMemoryRepository : MemoryRepository {

    private val storage = mutableMapOf<String, MemoryEntry>()

    override suspend fun save(entry: MemoryEntry): AppResult<MemoryEntry> {
        storage[entry.id] = entry
        return AppResult.Success(entry)
    }

    override suspend fun update(entry: MemoryEntry): AppResult<MemoryEntry> {
        if (!storage.containsKey(entry.id)) {
            return AppResult.Error(IllegalArgumentException("Memory entry with ID ${entry.id} not found"))
        }
        storage[entry.id] = entry
        return AppResult.Success(entry)
    }

    override suspend fun delete(id: String): AppResult<Boolean> {
        val removed = storage.remove(id) != null
        return AppResult.Success(removed)
    }

    override suspend fun get(id: String): AppResult<MemoryEntry?> {
        return AppResult.Success(storage[id])
    }

    override suspend fun search(query: MemoryQuery): AppResult<MemoryResult> {
        val filtered = storage.values.filter { entry ->
            val matchesLevel = query.targetLevels.contains(entry.level)
            val matchesImportance = entry.importance >= query.minImportance
            val matchesQuery = query.query.isBlank() ||
                    entry.content.contains(query.query, ignoreCase = true) ||
                    entry.tags.any { it.contains(query.query, ignoreCase = true) }
            val matchesTags = query.tags.isEmpty() || entry.tags.any { query.tags.contains(it) }

            matchesLevel && matchesImportance && matchesQuery && matchesTags
        }.take(query.limit)

        return AppResult.Success(
            MemoryResult(
                query = query.query,
                entries = filtered,
                snippets = filtered.map { it.content },
                totalCount = filtered.size,
                relevanceScore = if (filtered.isNotEmpty()) 0.9f else 0.0f
            )
        )
    }

    override suspend fun listRelevant(input: String, limit: Int): AppResult<List<MemoryEntry>> {
        val queryResult = search(MemoryQuery(query = input, limit = limit))
        return when (queryResult) {
            is AppResult.Success -> AppResult.Success(queryResult.data.entries)
            is AppResult.Error -> AppResult.Error(queryResult.exception, queryResult.message)
            is AppResult.Loading -> AppResult.Success(emptyList())
        }
    }
}
