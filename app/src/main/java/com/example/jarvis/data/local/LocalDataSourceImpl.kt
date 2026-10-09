package com.example.jarvis.data.local

import com.example.jarvis.data.models.TaskModel

class LocalDataSourceImpl : LocalDataSource {
    private val tasks = mutableListOf(
        TaskModel("1", "Revisar arquitectura de JARVIS", true),
        TaskModel("2", "Implementar Fase 2 y Fase 3", false),
        TaskModel("3", "Probar orquestador de agentes", false)
    )

    override suspend fun getLocalTasks(): List<TaskModel> {
        return tasks
    }

    override suspend fun saveLocalTask(task: TaskModel) {
        tasks.add(task)
    }
}
