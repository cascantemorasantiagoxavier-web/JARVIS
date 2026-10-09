package com.example.jarvis.domain.agents

import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority

interface ProductivityAgent : Agent {
    suspend fun manageTasks()
    suspend fun createTask(title: String, description: String = "", priority: TaskPriority = TaskPriority.MEDIUM): Task
    suspend fun completeTask(taskId: String): Boolean
    suspend fun searchTasks(query: String): List<Task>
}
