package com.example.jarvis.domain.memory.models

import java.util.UUID

enum class MemoryType {
    USER_PREFERENCE,
    USER_CONTEXT,
    PERSON,
    PROJECT_CONTEXT,
    DECISION,
    HABIT,
    GOAL,
    IMPORTANT_FACT,
    TEMPORARY_CONTEXT
}

enum class MemoryImportance(val weight: Float) {
    CRITICAL(1.0f),
    HIGH(0.8f),
    NORMAL(0.5f),
    LOW(0.2f)
}

enum class MemoryConfidence(val score: Float) {
    HIGH(0.9f),
    MEDIUM(0.6f),
    LOW(0.3f)
}

enum class MemorySource {
    USER_EXPLICIT,
    USER_CONFIRMED,
    SYSTEM,
    AGENT_INFERRED,
    IMPORTED,
    OBSIDIAN
}

enum class MemoryStatus {
    ACTIVE,
    ARCHIVED,
    EXPIRED,
    SUPERSEDED,
    REVIEW_REQUIRED
}

enum class MemoryDecisionAction {
    SHOULD_STORE,
    SHOULD_UPDATE,
    SHOULD_IGNORE,
    REQUIRES_REVIEW
}

data class MemoryDecisionResult(
    val action: MemoryDecisionAction,
    val record: MemoryRecord? = null,
    val existingRecordId: String? = null,
    val reasoning: String = ""
)

data class MemoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val type: MemoryType = MemoryType.IMPORTANT_FACT,
    val content: String,
    val importance: MemoryImportance = MemoryImportance.NORMAL,
    val confidence: MemoryConfidence = MemoryConfidence.MEDIUM,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lastAccessedAt: Long = createdAt,
    val source: MemorySource = MemorySource.USER_EXPLICIT,
    val tags: List<String> = emptyList(),
    val relatedProject: String? = null,
    val expiration: Long? = null,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val level: MemoryLevel = MemoryLevel.LONG_TERM
)
