package com.example.jarvis.domain.memory.models

import java.util.UUID

/**
 * Memory Tier levels.
 */
enum class MemoryLevel {
    WORKING,     // Short-term active context during conversation
    SESSION,     // Current session timeline & history
    LONG_TERM,   // Persisted user facts, preferences, decisions, history
    KNOWLEDGE    // Structured documentary knowledge (e.g. from Obsidian)
}

/**
 * Additional metadata attached to a memory entry.
 */
data class MemoryMetadata(
    val tags: List<String> = emptyList(),
    val source: String = "user",
    val confidence: Float = 1.0f,
    val custom: Map<String, String> = emptyMap()
)

/**
 * Unified model for a memory entry.
 */
data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val level: MemoryLevel = MemoryLevel.LONG_TERM,
    val importance: Float = 0.5f,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
    val source: String = "user",
    val confidence: Float = 1.0f,
    val metadata: MemoryMetadata = MemoryMetadata(tags = tags, source = source, confidence = confidence)
)

/**
 * Query container for memory search.
 */
data class MemoryQuery(
    val query: String,
    val targetLevels: List<MemoryLevel> = listOf(MemoryLevel.LONG_TERM, MemoryLevel.WORKING),
    val tags: List<String> = emptyList(),
    val minImportance: Float = 0.0f,
    val limit: Int = 10
)

/**
 * Result container returned from a memory retrieval.
 */
data class MemoryResult(
    val query: String,
    val entries: List<MemoryEntry> = emptyList(),
    val snippets: List<String> = emptyList(),
    val totalCount: Int = entries.size,
    val relevanceScore: Float = 1.0f
)

/**
 * Policy decisions for memory persistence.
 */
enum class MemoryPolicyDecision {
    SAVE,           // New relevant memory to persist
    UPDATE,         // Existing memory needs content update
    IGNORE,         // Trivial, noise, or duplicate memory
    DELETE_REVIEW   // Destructive operation requiring explicit user confirmation
}

/**
 * Structured output decision from MemoryPolicy evaluation.
 */
data class MemoryWriteDecision(
    val decision: MemoryPolicyDecision,
    val targetEntry: MemoryEntry? = null,
    val existingEntryId: String? = null,
    val reasoning: String = "",
    val requiresConfirmation: Boolean = (decision == MemoryPolicyDecision.DELETE_REVIEW)
)
