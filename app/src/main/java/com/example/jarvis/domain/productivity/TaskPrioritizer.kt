package com.example.jarvis.domain.productivity

import com.example.jarvis.domain.projects.Project
import com.example.jarvis.domain.tasks.Task

/**
 * Calculated priority analysis result for a task.
 */
data class TaskPriorityResult(
    val task: Task,
    val calculatedScore: Float,
    val urgencyReason: String,
    val isOverdue: Boolean,
    val isBlockedByDependencies: Boolean,
    val breakdown: Map<String, Float>
)

/**
 * Interface for non-arbitrary, explicit task prioritization.
 */
interface TaskPrioritizer {
    fun prioritize(
        task: Task,
        project: Project? = null,
        currentTime: Long = System.currentTimeMillis()
    ): TaskPriorityResult

    fun rankTasks(
        tasks: List<Task>,
        projects: List<Project> = emptyList(),
        currentTime: Long = System.currentTimeMillis()
    ): List<TaskPriorityResult>
}
