package com.example.jarvis.data.repository

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.data.models.TaskModel

interface Repository {
    suspend fun getTasks(): AppResult<List<TaskModel>>
}
