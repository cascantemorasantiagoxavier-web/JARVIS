package com.example.jarvis.core.tasks

import com.example.jarvis.core.tools.local.DueSpec
import com.example.jarvis.core.tools.local.NaturalDateParser
import com.example.jarvis.core.util.TimeSource
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

enum class TaskPriority(val label: String) { LOW("Baja"), MEDIUM("Media"), HIGH("Alta") }

enum class TaskStatus { PENDING, DONE }

data class TaskItem(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val due: LocalDateTime? = null,
    val hasTime: Boolean = false,
    val status: TaskStatus = TaskStatus.PENDING,
    val remind: Boolean = false,
    val createdAt: Instant,
    val completedAt: Instant? = null,
) {
    fun isOverdue(now: LocalDateTime): Boolean {
        if (status == TaskStatus.DONE || due == null) return false
        return if (hasTime) due.isBefore(now) else due.toLocalDate().isBefore(now.toLocalDate())
    }

    /** Momento del aviso: la hora indicada, o las 09:00 si la tarea es de día completo. */
    fun reminderAt(): LocalDateTime? = due?.let { if (hasTime) it else it.toLocalDate().atTime(LocalTime.of(9, 0)) }
}

interface TaskRepository {
    suspend fun insert(t: TaskItem): Long
    suspend fun update(t: TaskItem): Boolean
    suspend fun delete(id: Long): Boolean
    suspend fun get(id: Long): TaskItem?
    suspend fun all(): List<TaskItem>
}

/** Programación real de avisos (AlarmManager/WorkManager en Android). */
interface ReminderScheduler {
    /** Devuelve cómo quedó programado; lanza si no fue posible. */
    fun schedule(task: TaskItem): ReminderScheduleResult
    fun cancel(taskId: Long)
}

sealed interface ReminderScheduleResult {
    data class Exact(val at: LocalDateTime) : ReminderScheduleResult
    data class Inexact(val at: LocalDateTime, val reason: String) : ReminderScheduleResult
    data class NotAllowed(val reason: String) : ReminderScheduleResult
}

enum class TaskFilter { PENDING, DONE, OVERDUE, TODAY, ALL }

data class CreatedTask(val task: TaskItem, val reminder: ReminderScheduleResult?)

class TaskService(
    private val repo: TaskRepository,
    private val scheduler: ReminderScheduler,
    private val time: TimeSource,
) {
    fun parseDue(text: String?): DueSpec? = text?.takeIf { it.isNotBlank() }?.let { NaturalDateParser.parse(it, time.zonedNow()) }

    suspend fun create(
        title: String,
        description: String = "",
        priority: TaskPriority = TaskPriority.MEDIUM,
        dueText: String? = null,
        remind: Boolean = false,
    ): CreatedTask {
        val clean = title.trim()
        require(clean.length >= 2) { "La tarea necesita un título." }
        val due = parseDue(dueText)
        if (dueText != null && dueText.isNotBlank() && due == null) {
            throw IllegalArgumentException("No entendí la fecha \"$dueText\". Prueba con \"mañana a las 3 pm\" o \"el 5 de noviembre\".")
        }
        val item = TaskItem(
            title = clean, description = description.trim(), priority = priority,
            due = due?.dateTime, hasTime = due?.hasTime ?: false,
            remind = remind && due != null, createdAt = time.now(),
        )
        val id = repo.insert(item)
        check(id > 0) { "No se pudo guardar la tarea." }
        val saved = item.copy(id = id)
        val reminder = if (saved.remind) scheduler.schedule(saved) else null
        return CreatedTask(saved, reminder)
    }

    suspend fun complete(id: Long): TaskItem? {
        val t = repo.get(id) ?: return null
        val done = t.copy(status = TaskStatus.DONE, completedAt = time.now())
        if (!repo.update(done)) return null
        scheduler.cancel(id)
        return done
    }

    suspend fun delete(id: Long): Boolean {
        val ok = repo.delete(id)
        if (ok) scheduler.cancel(id)
        return ok
    }

    suspend fun list(filter: TaskFilter): List<TaskItem> {
        val now = time.zonedNow().toLocalDateTime()
        val today = now.toLocalDate()
        return repo.all().filter {
            when (filter) {
                TaskFilter.PENDING -> it.status == TaskStatus.PENDING
                TaskFilter.DONE -> it.status == TaskStatus.DONE
                TaskFilter.OVERDUE -> it.isOverdue(now)
                TaskFilter.TODAY -> it.status == TaskStatus.PENDING && it.due?.toLocalDate() == today
                TaskFilter.ALL -> true
            }
        }.sortedWith(compareBy<TaskItem>({ it.status }, { it.due ?: LocalDateTime.MAX }, { -it.priority.ordinal }))
    }

    /** Reprograma avisos pendientes (tras reiniciar el teléfono). */
    suspend fun rescheduleAll(): Int {
        val now = time.zonedNow().toLocalDateTime()
        var n = 0
        for (t in repo.all()) {
            val at = t.reminderAt() ?: continue
            if (t.remind && t.status == TaskStatus.PENDING && at.isAfter(now)) {
                scheduler.schedule(t)
                n++
            }
        }
        return n
    }
}
