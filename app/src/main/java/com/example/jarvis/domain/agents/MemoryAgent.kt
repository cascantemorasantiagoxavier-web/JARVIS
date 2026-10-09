package com.example.jarvis.domain.agents

import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel

interface MemoryAgent : Agent {
    suspend fun organizeMemories()
    suspend fun searchMemory(query: String, level: MemoryLevel = MemoryLevel.LONG_TERM): List<MemoryEntry>
    suspend fun memorize(content: String, level: MemoryLevel = MemoryLevel.LONG_TERM, importance: Float = 0.5f): String
    suspend fun updateMemory(id: String, newContent: String): Boolean
    suspend fun detectDuplicates(content: String): Boolean
    suspend fun detectConflicts(content: String): MemoryEntry?
}
