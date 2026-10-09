package com.example.jarvis.domain.tasks

import com.example.jarvis.core.common.AppResult

/**
 * Domain repository abstraction for Task operations.
 */
interface TaskRepository {
    suspend fun create(task: Task): AppResult<Task>
    suspend fun get(id: String): AppResult<Task?>
    suspend fun update(task: Task): AppResult<Task>
    suspend fun delete(id: String, isDestructiveConfirmed: Boolean = false): AppResult<Boolean>
    suspend fun list(status: TaskStatus? = null, projectId: String? = null): AppResult<List<Task>>
    suspend fun search(query: String): AppResult<List<Task>>
    suspend fun complete(id: String): AppResult<Task>
    suspend fun restore(id: String): AppResult<Task>
}
