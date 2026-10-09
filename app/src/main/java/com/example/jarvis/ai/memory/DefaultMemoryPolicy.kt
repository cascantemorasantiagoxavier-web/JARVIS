package com.example.jarvis.ai.memory

import com.example.jarvis.domain.memory.MemoryPolicy
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryPolicyDecision
import com.example.jarvis.domain.memory.models.MemoryWriteDecision

/**
 * Default implementation of MemoryPolicy for evaluating persistence suitability.
 */
class DefaultMemoryPolicy : MemoryPolicy {

    private val trivialPatterns = listOf("hola", "adiós", "chao", "ok", "gracias", "hello", "hi", "thanks")

    override suspend fun evaluate(
        candidateContent: String,
        candidateLevel: MemoryLevel,
        candidateSource: String,
        candidateImportance: Float,
        existingEntries: List<MemoryEntry>
    ): MemoryWriteDecision {
        val trimmed = candidateContent.trim()
        if (trimmed.isBlank()) {
            return MemoryWriteDecision(
                decision = MemoryPolicyDecision.IGNORE,
                reasoning = "Blank content ignored"
            )
        }

        // Triviality check
        if (trivialPatterns.any { trimmed.equals(it, ignoreCase = true) }) {
            return MemoryWriteDecision(
                decision = MemoryPolicyDecision.IGNORE,
                reasoning = "Trivial greeting or acknowledgement ignored"
            )
        }

        // Exact or near duplicate check
        val duplicate = existingEntries.find {
            it.content.equals(trimmed, ignoreCase = true) && it.level == candidateLevel
        }
        if (duplicate != null) {
            return MemoryWriteDecision(
                decision = MemoryPolicyDecision.IGNORE,
                existingEntryId = duplicate.id,
                reasoning = "Exact duplicate entry already exists in level $candidateLevel"
            )
        }

        // Check for potential update/contradiction by key topic match
        val existingUpdateTarget = existingEntries.find { existing ->
            existing.level == candidateLevel &&
                    extractTopicKey(existing.content) == extractTopicKey(trimmed) &&
                    extractTopicKey(trimmed).isNotBlank()
        }

        if (existingUpdateTarget != null) {
            val updatedEntry = existingUpdateTarget.copy(
                content = trimmed,
                importance = maxOf(existingUpdateTarget.importance, candidateImportance),
                timestamp = System.currentTimeMillis()
            )
            return MemoryWriteDecision(
                decision = MemoryPolicyDecision.UPDATE,
                targetEntry = updatedEntry,
                existingEntryId = existingUpdateTarget.id,
                reasoning = "Updating existing topic key '${extractTopicKey(trimmed)}'"
            )
        }

        // Low importance threshold check for long-term memory
        if (candidateLevel == MemoryLevel.LONG_TERM && candidateImportance < 0.2f) {
            return MemoryWriteDecision(
                decision = MemoryPolicyDecision.IGNORE,
                reasoning = "Candidate importance ($candidateImportance) below long-term threshold (0.2)"
            )
        }

        // High importance deletion or overwrite check
        if (candidateContent.startsWith("DELETE:", ignoreCase = true)) {
            return MemoryWriteDecision(
                decision = MemoryPolicyDecision.DELETE_REVIEW,
                reasoning = "Destructive memory removal requires user confirmation",
                requiresConfirmation = true
            )
        }

        // Default: SAVE
        val newEntry = MemoryEntry(
            content = trimmed,
            level = candidateLevel,
            importance = candidateImportance,
            source = candidateSource
        )

        return MemoryWriteDecision(
            decision = MemoryPolicyDecision.SAVE,
            targetEntry = newEntry,
            reasoning = "Valid candidate approved for persistence"
        )
    }

    private fun extractTopicKey(content: String): String {
        val lower = content.lowercase()
        return when {
            lower.contains("nombre") -> "user_name"
            lower.contains("preferencia") || lower.contains("me gusta") -> "user_preference"
            lower.contains("proyecto") -> "user_project"
            else -> ""
        }
    }
}
