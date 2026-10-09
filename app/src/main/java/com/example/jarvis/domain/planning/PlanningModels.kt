package com.example.jarvis.domain.planning

import com.example.jarvis.domain.tasks.Task
import java.util.UUID

/**
 * Model representing a high-level user goal.
 */
data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val targetDate: Long? = null,
    val projectId: String? = null
)

/**
 * Immediate actionable step derived from a task or plan.
 */
data class NextAction(
    val id: String = UUID.randomUUID().toString(),
    val actionText: String,
    val taskId: String? = null,
    val isBlocked: Boolean = false,
    val blockReason: String? = null
)

/**
 * Step inside a structured Plan.
 */
data class PlanStep(
    val stepNumber: Int,
    val title: String,
    val description: String = "",
    val taskId: String? = null,
    val nextActions: List<NextAction> = emptyList(),
    val dependencies: List<String> = emptyList()
)

/**
 * Complete plan object mapping Goal -> Project -> Steps -> Tasks -> Next Actions.
 */
data class Plan(
    val id: String = UUID.randomUUID().toString(),
    val goalId: String,
    val projectId: String? = null,
    val steps: List<PlanStep> = emptyList(),
    val nextActions: List<NextAction> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
