package com.example.jarvis.data.local

import com.example.jarvis.data.models.TaskModel

interface LocalDataSource {
    suspend fun getLocalTasks(): List<TaskModel>
    suspend fun saveLocalTask(task: TaskModel)
}
