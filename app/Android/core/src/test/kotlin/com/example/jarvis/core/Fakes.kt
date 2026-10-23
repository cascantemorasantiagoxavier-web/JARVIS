package com.example.jarvis.core

import com.example.jarvis.core.brain.ConversationStore
import com.example.jarvis.core.business.Prospect
import com.example.jarvis.core.business.ProspectRepository
import com.example.jarvis.core.device.ActionResult
import com.example.jarvis.core.device.BatteryInfo
import com.example.jarvis.core.device.CalendarEvent
import com.example.jarvis.core.device.CalendarRead
import com.example.jarvis.core.device.DeviceGateway
import com.example.jarvis.core.device.DeviceInfo
import com.example.jarvis.core.device.InstalledApp
import com.example.jarvis.core.device.NetworkInfo
import com.example.jarvis.core.device.SpecialApp
import com.example.jarvis.core.memory.MemoryItem
import com.example.jarvis.core.memory.MemoryKind
import com.example.jarvis.core.memory.MemoryRepository
import com.example.jarvis.core.model.ChatEntry
import com.example.jarvis.core.model.UiEvent
import com.example.jarvis.core.model.UiEventSink
import com.example.jarvis.core.notes.NoteChunk
import com.example.jarvis.core.notes.NoteIndexRepository
import com.example.jarvis.core.notes.NoteIndexStats
import com.example.jarvis.core.tasks.ReminderScheduleResult
import com.example.jarvis.core.tasks.ReminderScheduler
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.tasks.TaskRepository
import com.example.jarvis.core.text.TextNormalizer
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

class InMemoryMemoryRepo : MemoryRepository {
    val items = linkedMapOf<Long, MemoryItem>()
    private val ids = AtomicLong(1)
    override suspend fun insert(item: MemoryItem): Long = ids.getAndIncrement().also { items[it] = item.copy(id = it) }
    override suspend fun update(item: MemoryItem): Boolean = items.containsKey(item.id).also { if (it) items[item.id] = item }
    override suspend fun delete(id: Long): Boolean = items.remove(id) != null
    override suspend fun get(id: Long): MemoryItem? = items[id]
    override suspend fun all(): List<MemoryItem> = items.values.toList()
}

/**
 * Repositorio de memoria respaldado por archivo: permite probar que los recuerdos sobreviven
 * a "cerrar y reabrir" el almacenamiento (en Android, la persistencia real es Room/SQLite).
 */
class FileMemoryRepo(private val file: File) : MemoryRepository {
    private fun load(): MutableList<MemoryItem> = if (!file.exists()) mutableListOf() else file.readLines().filter { it.isNotBlank() }.map { line ->
        val p = line.split('\t')
        MemoryItem(p[0].toLong(), MemoryKind.valueOf(p[1]), p[2].replace("\\n", "\n"), p[3].toBoolean(), Instant.parse(p[4]), Instant.parse(p[5]), p[6])
    }.toMutableList()

    private fun save(list: List<MemoryItem>) {
        file.writeText(list.joinToString("\n") { listOf(it.id, it.kind, it.content.replace("\n", "\\n"), it.isPrivate, it.createdAt, it.updatedAt, it.source).joinToString("\t") })
    }

    override suspend fun insert(item: MemoryItem): Long {
        val list = load()
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        list += item.copy(id = id)
        save(list)
        return id
    }

    override suspend fun update(item: MemoryItem): Boolean {
        val list = load()
        val i = list.indexOfFirst { it.id == item.id }
        if (i < 0) return false
        list[i] = item
        save(list)
        return true
    }

    override suspend fun delete(id: Long): Boolean {
        val list = load()
        val removed = list.removeIf { it.id == id }
        save(list)
        return removed
    }

    override suspend fun get(id: Long): MemoryItem? = load().firstOrNull { it.id == id }
    override suspend fun all(): List<MemoryItem> = load()
}

class InMemoryTaskRepo : TaskRepository {
    val items = linkedMapOf<Long, TaskItem>()
    private val ids = AtomicLong(1)
    override suspend fun insert(t: TaskItem): Long = ids.getAndIncrement().also { items[it] = t.copy(id = it) }
    override suspend fun update(t: TaskItem): Boolean = items.containsKey(t.id).also { if (it) items[t.id] = t }
    override suspend fun delete(id: Long): Boolean = items.remove(id) != null
    override suspend fun get(id: Long): TaskItem? = items[id]
    override suspend fun all(): List<TaskItem> = items.values.toList()
}

class FakeScheduler(var exactAllowed: Boolean = true) : ReminderScheduler {
    val scheduled = mutableMapOf<Long, LocalDateTime>()
    val cancelled = mutableListOf<Long>()
    override fun schedule(task: TaskItem): ReminderScheduleResult {
        val at = task.reminderAt() ?: return ReminderScheduleResult.NotAllowed("sin fecha")
        scheduled[task.id] = at
        return if (exactAllowed) ReminderScheduleResult.Exact(at) else ReminderScheduleResult.Inexact(at, "sin permiso de alarmas exactas")
    }
    override fun cancel(taskId: Long) {
        scheduled.remove(taskId)
        cancelled += taskId
    }
}

class InMemoryProspectRepo : ProspectRepository {
    val items = linkedMapOf<Long, Prospect>()
    private val ids = AtomicLong(1)
    override suspend fun findByBusinessId(businessId: String) = items.values.firstOrNull { it.businessId == businessId }
    override suspend fun get(id: Long) = items[id]
    override suspend fun insert(p: Prospect): Long = ids.getAndIncrement().also { items[it] = p.copy(id = it) }
    override suspend fun update(p: Prospect) { items[p.id] = p }
    override suspend fun delete(id: Long) = items.remove(id) != null
    override suspend fun all() = items.values.toList()
}

class InMemoryNoteIndex : NoteIndexRepository {
    val chunks = mutableListOf<NoteChunk>()
    override suspend fun replaceNote(path: String, chunks: List<NoteChunk>) {
        this.chunks.removeAll { it.notePath == path }
        this.chunks += chunks
    }
    override suspend fun removeNotesNotIn(paths: Set<String>) { chunks.removeAll { it.notePath !in paths } }
    override suspend fun candidates(terms: List<String>, limit: Int): List<NoteChunk> =
        chunks.filter { c -> val t = TextNormalizer.fold(c.noteTitle + " " + c.heading + " " + c.text + " " + c.tags.joinToString(" ")); terms.any { t.contains(it) } }.take(limit)
    override suspend fun stats() = NoteIndexStats(chunks.map { it.notePath }.distinct().size, chunks.size, chunks.filter { it.isPrivate }.map { it.notePath }.distinct().size, null)
}

class InMemoryConversation : ConversationStore {
    val entries = mutableListOf<ChatEntry>()
    private val ids = AtomicLong(1)
    override suspend fun recent(limit: Int) = entries.takeLast(limit)
    override suspend fun append(entry: ChatEntry): Long = ids.getAndIncrement().also { entries += entry.copy(id = it) }
    override suspend fun update(entry: ChatEntry) { val i = entries.indexOfFirst { it.id == entry.id }; if (i >= 0) entries[i] = entry }
    override suspend fun clear() = entries.clear()
}

class RecordingUi : UiEventSink {
    val events = mutableListOf<UiEvent>()
    override fun emit(event: UiEvent) { events += event }
}

class FakeDevice : DeviceGateway {
    val actions = mutableListOf<String>()
    var calendarAllowed = true
    var events = listOf<CalendarEvent>()
    var apps = listOf(InstalledApp("Spotify", "com.spotify.music"), InstalledApp("WhatsApp", "com.whatsapp"), InstalledApp("Maps", "com.google.android.apps.maps"))
    override suspend fun battery() = BatteryInfo(76, charging = false)
    override suspend fun network() = NetworkInfo(connected = true, validated = true, type = "Wi-Fi")
    override fun deviceInfo() = DeviceInfo("Xiaomi", "Redmi Note", "15", 35)
    override suspend fun installedApps() = apps
    override suspend fun openApp(packageName: String): ActionResult { actions += "open:$packageName"; return ActionResult.Done("") }
    override suspend fun openSpecial(kind: SpecialApp): ActionResult { actions += "special:$kind"; return ActionResult.Done("") }
    override suspend fun openLocation(lat: Double, lon: Double, label: String): ActionResult { actions += "geo:$lat,$lon"; return ActionResult.Done("") }
    override suspend fun directions(lat: Double, lon: Double, label: String): ActionResult { actions += "route:$lat,$lon"; return ActionResult.Done("") }
    override suspend fun dial(number: String): ActionResult { actions += "dial:$number"; return ActionResult.Done("") }
    override suspend fun share(text: String, title: String?): ActionResult { actions += "share"; return ActionResult.Done("") }
    override suspend fun insertCalendarEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?): ActionResult { actions += "calendar:$title@$start"; return ActionResult.Done("") }
    override suspend fun calendarEvents(from: LocalDateTime, to: LocalDateTime): CalendarRead =
        if (calendarAllowed) CalendarRead.Events(events.filter { !it.start.isBefore(from) && !it.start.isAfter(to) }) else CalendarRead.PermissionMissing
}
