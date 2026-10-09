package com.example.jarvis.domain.tasks

import java.util.UUID

/**
 * Domain model representing a Task in JARVIS.
 */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val status: TaskStatus = TaskStatus.TODO,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dueDate: Long? = null,
    val projectId: String? = null,
    val tags: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val source: TaskSource = TaskSource.USER,
    val metadata: TaskMetadata = TaskMetadata(tags = tags)
) {
    /** Backwards-compatible getter for legacy UI and models */
    val isCompleted: Boolean
        get() = status == TaskStatus.COMPLETED
}
