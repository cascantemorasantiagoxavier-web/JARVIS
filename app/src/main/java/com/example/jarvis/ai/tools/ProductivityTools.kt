package com.example.jarvis.ai.tools

import com.example.jarvis.ai.productivity.DefaultTaskRepository
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.tasks.TaskStatus
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

object GlobalTaskHolder {
    val repository = DefaultTaskRepository()
}

class TaskListTool : Tool {
    override val name = "task_list"
    override val description = "Lista las tareas pendientes y de productividad del usuario"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val result = GlobalTaskHolder.repository.list(status = TaskStatus.TODO)
        if (result is AppResult.Success) {
            val tasks = result.data
            if (tasks.isEmpty()) {
                return "No tienes ninguna tarea pendiente en este momento, jefe."
            }
            val listStr = tasks.joinToString("\n") { t ->
                "• [${t.priority}] ${t.title}"
            }
            return "📋 Tareas pendientes (${tasks.size}):\n$listStr"
        }
        return "No fue posible consultar las tareas."
    }
}

class TaskCompleteTool : Tool {
    override val name = "task_complete"
    override val description = "Marca una tarea pendiente como completada"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: params["title"]?.toString() ?: params["id"]?.toString() ?: ""
        val listResult = GlobalTaskHolder.repository.list(status = TaskStatus.TODO)
        if (listResult is AppResult.Success) {
            val tasks = listResult.data
            val target = tasks.firstOrNull { it.title.contains(query, ignoreCase = true) || it.id == query }
                ?: tasks.firstOrNull()

            if (target != null) {
                val completeRes = GlobalTaskHolder.repository.complete(target.id)
                if (completeRes is AppResult.Success) {
                    return "✅ Tarea completada: '${target.title}'"
                }
            }
        }
        return "No encontré ninguna tarea pendiente coincidente para completar."
    }
}
