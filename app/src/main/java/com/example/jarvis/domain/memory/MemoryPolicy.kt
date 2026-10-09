package com.example.jarvis.domain.memory

import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryWriteDecision

/**
 * Domain policy abstraction for evaluating memory persistence suitability.
 */
interface MemoryPolicy {
    suspend fun evaluate(
        candidateContent: String,
        candidateLevel: MemoryLevel = MemoryLevel.LONG_TERM,
        candidateSource: String = "user",
        candidateImportance: Float = 0.5f,
        existingEntries: List<MemoryEntry> = emptyList()
    ): MemoryWriteDecision
}
