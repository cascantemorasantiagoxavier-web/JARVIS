package com.example.jarvis.domain.planning

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.projects.Project
import com.example.jarvis.domain.tasks.Task

/**
 * Interface for planning architecture without hardcoding LLM dependencies.
 */
interface PlanningEngine {
    suspend fun generatePlan(
        goal: Goal,
        project: Project? = null,
        constraints: List<String> = emptyList()
    ): AppResult<Plan>

    suspend fun decomposeGoal(goal: Goal): AppResult<List<Task>>

    suspend fun extractNextActions(plan: Plan, tasks: List<Task>): AppResult<List<NextAction>>
}
