package com.example.jarvis.ai.memory

import com.example.jarvis.domain.memory.models.*

class Memory2Policy {
    private val trivialPatterns = listOf("hola", "adiós", "ok", "gracias", "hello", "hi", "thanks", "cansado", "haciendo café")

    fun evaluate(candidate: MemoryRecord, existingRecords: List<MemoryRecord>): MemoryDecisionResult {
        val trimmed = candidate.content.trim()
        if (trimmed.isBlank()) {
            return MemoryDecisionResult(MemoryDecisionAction.SHOULD_IGNORE, reasoning = "Blank content ignored")
        }

        // Triviality check
        if (trivialPatterns.any { trimmed.contains(it, ignoreCase = true) }) {
            return MemoryDecisionResult(MemoryDecisionAction.SHOULD_IGNORE, reasoning = "Trivial or temporary context ignored")
        }

        // Expiry check for temporary memory
        if (candidate.expiration != null && candidate.expiration <= System.currentTimeMillis()) {
            return MemoryDecisionResult(MemoryDecisionAction.SHOULD_IGNORE, reasoning = "Expired temporary memory ignored")
        }

        // Deduplication check
        val duplicate = existingRecords.find { it.content.equals(trimmed, ignoreCase = true) && it.status == MemoryStatus.ACTIVE }
        if (duplicate != null) {
            return MemoryDecisionResult(
                action = MemoryDecisionAction.SHOULD_UPDATE,
                record = duplicate.copy(lastAccessedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()),
                existingRecordId = duplicate.id,
                reasoning = "Exact duplicate found, updating last access time"
            )
        }

        // Contradiction / Topic update check
        val contradiction = existingRecords.find { existing ->
            existing.status == MemoryStatus.ACTIVE &&
                    isSameTopic(existing.content, trimmed) &&
                    !existing.content.equals(trimmed, ignoreCase = true)
        }

        if (contradiction != null) {
            if (candidate.confidence.ordinal <= contradiction.confidence.ordinal) {
                val updated = candidate.copy(
                    status = MemoryStatus.ACTIVE,
                    updatedAt = System.currentTimeMillis()
                )
                return MemoryDecisionResult(
                    action = MemoryDecisionAction.SHOULD_UPDATE,
                    record = updated,
                    existingRecordId = contradiction.id,
                    reasoning = "Contradiction detected on same topic; candidate supersedes existing memory"
                )
            } else {
                return MemoryDecisionResult(
                    action = MemoryDecisionAction.REQUIRES_REVIEW,
                    record = candidate.copy(status = MemoryStatus.REVIEW_REQUIRED),
                    existingRecordId = contradiction.id,
                    reasoning = "Ambiguous contradiction detected; review required"
                )
            }
        }

        return MemoryDecisionResult(
            action = MemoryDecisionAction.SHOULD_STORE,
            record = candidate,
            reasoning = "Valid stable memory approved for storage"
        )
    }

    private fun isSameTopic(c1: String, c2: String): Boolean {
        val l1 = c1.lowercase()
        val l2 = c2.lowercase()
        return (l1.contains("prefer") && l2.contains("prefer")) ||
                (l1.contains("nombre") && l2.contains("nombre")) ||
                (l1.contains("proyecto") && l2.contains("proyecto")) ||
                (l1.contains("tema") && l2.contains("tema"))
    }
}
