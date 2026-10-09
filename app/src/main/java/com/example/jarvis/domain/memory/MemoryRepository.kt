package com.example.jarvis.domain.memory

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryQuery
import com.example.jarvis.domain.memory.models.MemoryResult

/**
 * Domain repository abstraction for decoupled memory persistence operations.
 */
interface MemoryRepository {
    suspend fun save(entry: MemoryEntry): AppResult<MemoryEntry>
    suspend fun update(entry: MemoryEntry): AppResult<MemoryEntry>
    suspend fun delete(id: String): AppResult<Boolean>
    suspend fun get(id: String): AppResult<MemoryEntry?>
    suspend fun search(query: MemoryQuery): AppResult<MemoryResult>
    suspend fun listRelevant(input: String, limit: Int = 5): AppResult<List<MemoryEntry>>
}
