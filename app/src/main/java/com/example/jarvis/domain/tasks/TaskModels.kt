package com.example.jarvis.domain.tasks

/**
 * Lifecycle status of a task.
 */
enum class TaskStatus {
    INBOX,
    TODO,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    CANCELLED
}

/**
 * Priority levels for tasks.
 */
enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}

/**
 * Origin source of a task.
 */
enum class TaskSource {
    USER,
    JARVIS_SUGGESTION,
    ROUTINE,
    EVENT,
    OBSIDIAN
}

/**
 * Flexible metadata associated with a task.
 */
data class TaskMetadata(
    val tags: List<String> = emptyList(),
    val customProps: Map<String, String> = emptyMap()
)
