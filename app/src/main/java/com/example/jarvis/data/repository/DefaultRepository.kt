package com.example.jarvis.data.repository

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.data.local.LocalDataSource
import com.example.jarvis.data.models.TaskModel
import com.example.jarvis.data.remote.RemoteDataSource

class DefaultRepository(
    private val localDataSource: LocalDataSource,
    private val remoteDataSource: RemoteDataSource
) : Repository {
    override suspend fun getTasks(): AppResult<List<TaskModel>> {
        return try {
            val tasks = localDataSource.getLocalTasks()
            AppResult.Success(tasks)
        } catch (e: Exception) {
            AppResult.Error(e, e.message)
        }
    }
}
