package com.example.jarvis.ai.productivity

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskRepository
import com.example.jarvis.domain.tasks.TaskStatus

/**
 * In-memory local thread-safe implementation of TaskRepository.
 */
class DefaultTaskRepository : TaskRepository {

    private val tasksStore = mutableMapOf<String, Task>()

    override suspend fun create(task: Task): AppResult<Task> {
        tasksStore[task.id] = task
        return AppResult.Success(task)
    }

    override suspend fun get(id: String): AppResult<Task?> {
        return AppResult.Success(tasksStore[id])
    }

    override suspend fun update(task: Task): AppResult<Task> {
        if (!tasksStore.containsKey(task.id)) {
            return AppResult.Error(IllegalArgumentException("Task with ID ${task.id} not found"))
        }
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        tasksStore[task.id] = updated
        return AppResult.Success(updated)
    }

    override suspend fun delete(id: String, isDestructiveConfirmed: Boolean): AppResult<Boolean> {
        if (!isDestructiveConfirmed) {
            return AppResult.Error(
                SecurityException("Destructive action 'delete task' requires explicit user confirmation.")
            )
        }
        val removed = tasksStore.remove(id) != null
        return AppResult.Success(removed)
    }

    override suspend fun list(status: TaskStatus?, projectId: String?): AppResult<List<Task>> {
        val filtered = tasksStore.values.filter { task ->
            (status == null || task.status == status) &&
                    (projectId == null || task.projectId == projectId)
        }
        return AppResult.Success(filtered)
    }

    override suspend fun search(query: String): AppResult<List<Task>> {
        if (query.isBlank()) {
            return AppResult.Success(tasksStore.values.toList())
        }
        val matches = tasksStore.values.filter { task ->
            task.title.contains(query, ignoreCase = true) ||
                    task.description.contains(query, ignoreCase = true) ||
                    task.tags.any { it.contains(query, ignoreCase = true) }
        }
        return AppResult.Success(matches)
    }

    override suspend fun complete(id: String): AppResult<Task> {
        val existing = tasksStore[id]
            ?: return AppResult.Error(IllegalArgumentException("Task $id not found"))
        val completed = existing.copy(
            status = TaskStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
        tasksStore[id] = completed
        return AppResult.Success(completed)
    }

    override suspend fun restore(id: String): AppResult<Task> {
        val existing = tasksStore[id]
            ?: return AppResult.Error(IllegalArgumentException("Task $id not found"))
        val restored = existing.copy(
            status = TaskStatus.TODO,
            updatedAt = System.currentTimeMillis()
        )
        tasksStore[id] = restored
        return AppResult.Success(restored)
    }
}
