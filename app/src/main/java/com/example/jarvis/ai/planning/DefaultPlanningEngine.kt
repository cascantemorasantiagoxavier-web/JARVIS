package com.example.jarvis.ai.planning

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.planning.*
import com.example.jarvis.domain.projects.Project
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority
import com.example.jarvis.domain.tasks.TaskStatus

/**
 * Structural rule-based implementation of PlanningEngine.
 */
class DefaultPlanningEngine : PlanningEngine {

    override suspend fun generatePlan(
        goal: Goal,
        project: Project?,
        constraints: List<String>
    ): AppResult<Plan> {
        val tasks = decomposeGoal(goal)
        if (tasks is AppResult.Error) return AppResult.Error(tasks.exception, tasks.message)

        val taskList = (tasks as AppResult.Success).data
        val steps = taskList.mapIndexed { index, task ->
            PlanStep(
                stepNumber = index + 1,
                title = task.title,
                description = task.description,
                taskId = task.id,
                nextActions = listOf(NextAction(actionText = "Execute step ${index + 1}: ${task.title}", taskId = task.id))
            )
        }

        val allNextActions = steps.flatMap { it.nextActions }
        val plan = Plan(
            goalId = goal.id,
            projectId = project?.id ?: goal.projectId,
            steps = steps,
            nextActions = allNextActions
        )

        return AppResult.Success(plan)
    }

    override suspend fun decomposeGoal(goal: Goal): AppResult<List<Task>> {
        val title = goal.title
        val task1 = Task(
            title = "Analyze requirements for $title",
            description = "Initial breakdown and scope identification",
            projectId = goal.projectId,
            priority = TaskPriority.HIGH,
            status = TaskStatus.TODO
        )
        val task2 = Task(
            title = "Execute core steps for $title",
            description = "Implementation and execution of plan steps",
            projectId = goal.projectId,
            priority = TaskPriority.MEDIUM,
            status = TaskStatus.TODO,
            dependencies = listOf(task1.id)
        )
        val task3 = Task(
            title = "Verify and review $title",
            description = "Final validation and closure",
            projectId = goal.projectId,
            priority = TaskPriority.LOW,
            status = TaskStatus.TODO,
            dependencies = listOf(task2.id)
        )

        return AppResult.Success(listOf(task1, task2, task3))
    }

    override suspend fun extractNextActions(plan: Plan, tasks: List<Task>): AppResult<List<NextAction>> {
        val completedTaskIds = tasks.filter { it.status == TaskStatus.COMPLETED }.map { it.id }.toSet()
        val nextActions = mutableListOf<NextAction>()

        for (step in plan.steps) {
            val isBlocked = step.dependencies.any { depId -> !completedTaskIds.contains(depId) }
            val blockReason = if (isBlocked) "Blocked by incomplete dependency steps" else null

            step.nextActions.forEach { action ->
                nextActions.add(
                    action.copy(
                        isBlocked = isBlocked,
                        blockReason = blockReason
                    )
                )
            }
        }

        return AppResult.Success(nextActions)
    }
}
