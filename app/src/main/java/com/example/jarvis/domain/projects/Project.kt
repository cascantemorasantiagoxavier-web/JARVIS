package com.example.jarvis.domain.projects

import com.example.jarvis.domain.tasks.TaskPriority
import java.util.UUID

/**
 * Domain model representing a Project in JARVIS.
 */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val status: ProjectStatus = ProjectStatus.PLANNING,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deadline: Long? = null,
    val goal: String = "",
    val tags: List<String> = emptyList(),
    val nextAction: String = "",
    val relatedMemories: List<String> = emptyList(),
    val obsidianReference: String? = null,
    val taskIds: List<String> = emptyList(),
    val notePaths: List<String> = emptyList()
) {
    /** Backwards-compatible getter for legacy code */
    val summary: String
        get() = description.ifBlank { name }
}
