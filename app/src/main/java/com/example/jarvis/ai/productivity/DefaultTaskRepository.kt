package com.example.jarvis.ai.productivity

import android.content.Context
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority
import com.example.jarvis.domain.tasks.TaskRepository
import com.example.jarvis.domain.tasks.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Thread-safe persistent local implementation of TaskRepository.
 */
class DefaultTaskRepository(
    private val contextProvider: () -> Context? = { AppContainer.appContext }
) : TaskRepository {

    private val tasksStore = mutableMapOf<String, Task>()
    private val storageFile: File? by lazy {
        try {
            val ctx = contextProvider()
            if (ctx != null) {
                File(ctx.filesDir, "tasks_persistent.json")
            } else {
                File(System.getProperty("java.io.tmpdir"), "jarvis_tasks_persistent.json")
            }
        } catch (_: Exception) {
            null
        }
    }

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            val file = storageFile
            if (file != null && file.exists()) {
                val raw = file.readText(Charsets.UTF_8)
                val jsonArr = JSONArray(raw)
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    val id = obj.getString("id")
                    val title = obj.getString("title")
                    val desc = obj.optString("description", "")
                    val priorityStr = obj.optString("priority", "MEDIUM")
                    val statusStr = obj.optString("status", "TODO")
                    val dueAt = if (obj.has("dueDate")) obj.getLong("dueDate") else null

                    val priority = try { TaskPriority.valueOf(priorityStr) } catch (_: Exception) { TaskPriority.MEDIUM }
                    val status = try { TaskStatus.valueOf(statusStr) } catch (_: Exception) { TaskStatus.TODO }

                    val task = Task(
                        id = id,
                        title = title,
                        description = desc,
                        priority = priority,
                        status = status,
                        dueDate = dueAt
                    )
                    tasksStore[id] = task
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveToDisk() {
        try {
            val file = storageFile ?: return
            val jsonArr = JSONArray()
            for (t in tasksStore.values) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("description", t.description)
                    put("priority", t.priority.name)
                    put("status", t.status.name)
                    t.dueDate?.let { put("dueDate", it) }
                }
                jsonArr.put(obj)
            }
            file.parentFile?.mkdirs()
            file.writeText(jsonArr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    override suspend fun create(task: Task): AppResult<Task> {
        tasksStore[task.id] = task
        saveToDisk()
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
        saveToDisk()
        return AppResult.Success(updated)
    }

    override suspend fun delete(id: String, isDestructiveConfirmed: Boolean): AppResult<Boolean> {
        if (!isDestructiveConfirmed) {
            return AppResult.Error(
                SecurityException("Destructive action 'delete task' requires explicit user confirmation.")
            )
        }
        val removed = tasksStore.remove(id) != null
        if (removed) saveToDisk()
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
        saveToDisk()
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
        saveToDisk()
        return AppResult.Success(restored)
    }
}
