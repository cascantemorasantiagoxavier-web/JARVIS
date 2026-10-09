package com.example.jarvis.ai.productivity

import com.example.jarvis.domain.productivity.TaskPriorityResult
import com.example.jarvis.domain.productivity.TaskPrioritizer
import com.example.jarvis.domain.projects.Project
import com.example.jarvis.domain.projects.ProjectStatus
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority
import com.example.jarvis.domain.tasks.TaskStatus
import kotlin.math.max

/**
 * Deterministic implementation of TaskPrioritizer.
 */
class DefaultTaskPrioritizer : TaskPrioritizer {

    override fun prioritize(
        task: Task,
        project: Project?,
        currentTime: Long
    ): TaskPriorityResult {
        val breakdown = mutableMapOf<String, Float>()

        // 1. Explicit Priority Base Weight
        val baseScore = when (task.priority) {
            TaskPriority.URGENT -> 40.0f
            TaskPriority.HIGH -> 30.0f
            TaskPriority.MEDIUM -> 20.0f
            TaskPriority.LOW -> 10.0f
        }
        breakdown["basePriorityWeight"] = baseScore

        // 2. Deadline Proximity
        var deadlineScore = 0.0f
        var isOverdue = false
        var urgencyReason = "Normal schedule"

        if (task.dueDate != null) {
            val diffMs = task.dueDate - currentTime
            if (diffMs < 0) {
                isOverdue = true
                deadlineScore = 40.0f
                urgencyReason = "Overdue task!"
            } else {
                val hoursRemaining = diffMs / (1000 * 60 * 60)
                when {
                    hoursRemaining <= 24 -> {
                        deadlineScore = 30.0f
                        urgencyReason = "Due within 24 hours"
                    }
                    hoursRemaining <= 72 -> {
                        deadlineScore = 15.0f
                        urgencyReason = "Due within 3 days"
                    }
                    else -> {
                        deadlineScore = 5.0f
                        urgencyReason = "Due in distant future"
                    }
                }
            }
        }
        breakdown["deadlineWeight"] = deadlineScore

        // 3. Dependency Check
        val isBlocked = task.status == TaskStatus.BLOCKED || task.dependencies.isNotEmpty()
        val dependencyPenalty = if (isBlocked) -30.0f else 0.0f
        breakdown["dependencyPenalty"] = dependencyPenalty

        // 4. Age Bonus
        val ageDays = max(0L, (currentTime - task.createdAt) / (1000 * 60 * 60 * 24))
        val ageBonus = ageDays.coerceAtMost(10L).toFloat()
        breakdown["ageBonus"] = ageBonus

        // 5. Project Factor
        var projectWeight = 0.0f
        if (project != null) {
            projectWeight = when (project.status) {
                ProjectStatus.ACTIVE -> 10.0f
                ProjectStatus.BLOCKED -> -20.0f
                else -> 0.0f
            }
        }
        breakdown["projectFactor"] = projectWeight

        // Total calculated score
        val totalScore = (baseScore + deadlineScore + dependencyPenalty + ageBonus + projectWeight).coerceIn(0.0f, 100.0f)

        return TaskPriorityResult(
            task = task,
            calculatedScore = totalScore,
            urgencyReason = urgencyReason,
            isOverdue = isOverdue,
            isBlockedByDependencies = isBlocked,
            breakdown = breakdown
        )
    }

    override fun rankTasks(
        tasks: List<Task>,
        projects: List<Project>,
        currentTime: Long
    ): List<TaskPriorityResult> {
        val projectMap = projects.associateBy { it.id }
        return tasks.map { task ->
            val project = task.projectId?.let { projectMap[it] }
            prioritize(task, project, currentTime)
        }.sortedByDescending { it.calculatedScore }
    }
}
