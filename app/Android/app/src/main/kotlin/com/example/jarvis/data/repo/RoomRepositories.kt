package com.example.jarvis.data.repo

import com.example.jarvis.core.brain.ConversationStore
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.Prospect
import com.example.jarvis.core.business.ProspectRepository
import com.example.jarvis.core.business.ProspectStatus
import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.memory.MemoryItem
import com.example.jarvis.core.memory.MemoryKind
import com.example.jarvis.core.memory.MemoryRepository
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ChatEntry
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.Role
import com.example.jarvis.core.model.ToolRun
import com.example.jarvis.core.net.WebSearchResult
import com.example.jarvis.core.notes.NoteChunk
import com.example.jarvis.core.notes.NoteIndexRepository
import com.example.jarvis.core.notes.NoteIndexStats
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.tasks.TaskPriority
import com.example.jarvis.core.tasks.TaskRepository
import com.example.jarvis.core.tasks.TaskStatus
import com.example.jarvis.core.tools.ToolLogEntry
import com.example.jarvis.core.tools.ToolLogSink
import com.example.jarvis.data.db.ChatEntity
import com.example.jarvis.data.db.JarvisDatabase
import com.example.jarvis.data.db.MemoryEntity
import com.example.jarvis.data.db.NoteChunkEntity
import com.example.jarvis.data.db.ProspectEntity
import com.example.jarvis.data.db.TaskEntity
import com.example.jarvis.data.db.ToolLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime

/** Todas las operaciones Room se ejecutan fuera del hilo principal. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

class RoomMemoryRepository(private val db: JarvisDatabase) : MemoryRepository {
    override suspend fun insert(item: MemoryItem): Long = io { db.memories().insert(item.toEntity().copy(id = 0)) }
    override suspend fun update(item: MemoryItem): Boolean = io { db.memories().update(item.toEntity()) == 1 }
    override suspend fun delete(id: Long): Boolean = io { db.memories().delete(id) == 1 }
    override suspend fun get(id: Long): MemoryItem? = io { db.memories().get(id)?.toModel() }
    override suspend fun all(): List<MemoryItem> = io { db.memories().all().map { it.toModel() } }
}

fun MemoryItem.toEntity() = MemoryEntity(id, kind.name, content, isPrivate, createdAt.toEpochMilli(), updatedAt.toEpochMilli(), source)
fun MemoryEntity.toModel() = MemoryItem(
    id = id,
    kind = runCatching { MemoryKind.valueOf(kind) }.getOrDefault(MemoryKind.OTHER),
    content = content,
    isPrivate = isPrivate,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    source = source,
)

class RoomTaskRepository(private val db: JarvisDatabase) : TaskRepository {
    override suspend fun insert(t: TaskItem): Long = io { db.tasks().insert(t.toEntity().copy(id = 0)) }
    override suspend fun update(t: TaskItem): Boolean = io { db.tasks().update(t.toEntity()) == 1 }
    override suspend fun delete(id: Long): Boolean = io { db.tasks().delete(id) == 1 }
    override suspend fun get(id: Long): TaskItem? = io { db.tasks().get(id)?.toModel() }
    override suspend fun all(): List<TaskItem> = io { db.tasks().all().map { it.toModel() } }
}

fun TaskItem.toEntity() = TaskEntity(
    id, title, description, priority.name, due?.toString(), hasTime, status.name, remind,
    createdAt.toEpochMilli(), completedAt?.toEpochMilli(),
)

fun TaskEntity.toModel() = TaskItem(
    id = id,
    title = title,
    description = description,
    priority = runCatching { TaskPriority.valueOf(priority) }.getOrDefault(TaskPriority.MEDIUM),
    due = due?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
    hasTime = hasTime,
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.PENDING),
    remind = remind,
    createdAt = Instant.ofEpochMilli(createdAt),
    completedAt = completedAt?.let { Instant.ofEpochMilli(it) },
)

class RoomProspectRepository(private val db: JarvisDatabase) : ProspectRepository {
    override suspend fun findByBusinessId(businessId: String) = io { db.prospects().byBusiness(businessId)?.toModel() }
    override suspend fun get(id: Long) = io { db.prospects().get(id)?.toModel() }
    override suspend fun insert(p: Prospect): Long = io { db.prospects().insert(p.toEntity().copy(id = 0)) }
    override suspend fun update(p: Prospect) { io { db.prospects().update(p.toEntity()) } }
    override suspend fun delete(id: Long) = io { db.prospects().delete(id) == 1 }
    override suspend fun all() = io { db.prospects().all().map { it.toModel() } }
}

fun Prospect.toEntity() = ProspectEntity(
    id, businessId, name, category, address, province, canton, district, phone, website, email, lat, lon,
    locationApproximate, sourceName, sourceUrl, foundAt.toEpochMilli(), status.name, notes, nextAction,
    updatedAt.toEpochMilli(), snapshotJson,
)

fun ProspectEntity.toModel() = Prospect(
    id = id, businessId = businessId, name = name, category = category, address = address, province = province,
    canton = canton, district = district, phone = phone, website = website, email = email, lat = lat, lon = lon,
    locationApproximate = locationApproximate, sourceName = sourceName, sourceUrl = sourceUrl,
    foundAt = Instant.ofEpochMilli(foundAt),
    status = runCatching { ProspectStatus.valueOf(status) }.getOrDefault(ProspectStatus.NUEVO),
    notes = notes, nextAction = nextAction, updatedAt = Instant.ofEpochMilli(updatedAt), snapshotJson = snapshotJson,
)

/** Tarjetas que se conservan en el historial (las demás se reconstruyen con las pantallas). */
@Serializable
private data class StoredCards(
    val businesses: List<BusinessSearchResult> = emptyList(),
    val web: List<WebSearchResult> = emptyList(),
    val info: List<StoredInfo> = emptyList(),
)

@Serializable
private data class StoredInfo(val title: String, val lines: List<String>)

@Serializable
private data class StoredRun(val name: String, val ok: Boolean, val message: String, val durationMs: Long, val errorCode: String? = null)

class RoomConversationStore(private val db: JarvisDatabase) : ConversationStore {
    override suspend fun recent(limit: Int): List<ChatEntry> = io { db.chat().recent(limit).map { it.toModel() } }
    override suspend fun append(entry: ChatEntry): Long = io { db.chat().insert(entry.toEntity().copy(id = 0)) }
    override suspend fun update(entry: ChatEntry) { io { db.chat().update(entry.toEntity()) } }
    override suspend fun clear() = io { db.chat().clear() }

    private fun ChatEntry.toEntity(): ChatEntity {
        val stored = StoredCards(
            businesses = cards.filterIsInstance<ResultCard.Businesses>().map { it.result },
            web = cards.filterIsInstance<ResultCard.WebResults>().map { it.result },
            info = cards.filterIsInstance<ResultCard.Info>().map { StoredInfo(it.title, it.lines) },
        )
        val hasCards = stored.businesses.isNotEmpty() || stored.web.isNotEmpty() || stored.info.isNotEmpty()
        return ChatEntity(
            id = id, role = role.name, text = text, at = at.toEpochMilli(), source = source.name, mode = mode?.name,
            usedModel = usedModel, isError = isError, pending = pending,
            cardsJson = if (hasCards) JarvisJson.encodeToString(StoredCards.serializer(), stored) else null,
            toolRunsJson = if (toolRuns.isEmpty()) null else JarvisJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(StoredRun.serializer()),
                toolRuns.map { StoredRun(it.name, it.ok, it.message, it.durationMs, it.errorCode) },
            ),
        )
    }

    private fun ChatEntity.toModel(): ChatEntry {
        val stored = cardsJson?.let { runCatching { JarvisJson.decodeFromString(StoredCards.serializer(), it) }.getOrNull() }
        val cards = buildList<ResultCard> {
            stored?.businesses?.forEach { add(ResultCard.Businesses(it)) }
            stored?.web?.forEach { add(ResultCard.WebResults(it)) }
            stored?.info?.forEach { add(ResultCard.Info(it.title, it.lines)) }
        }
        val runs = toolRunsJson?.let {
            runCatching { JarvisJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(StoredRun.serializer()), it) }.getOrNull()
        }.orEmpty().map { ToolRun(it.name, it.ok, it.message, it.durationMs, it.errorCode) }
        return ChatEntry(
            id = id,
            role = runCatching { Role.valueOf(role) }.getOrDefault(Role.SYSTEM),
            text = if (pending) "(interrumpido)" else text,
            at = Instant.ofEpochMilli(at),
            source = runCatching { RequestSource.valueOf(source) }.getOrDefault(RequestSource.TEXT),
            cards = cards,
            toolRuns = runs,
            mode = mode?.let { runCatching { AssistantMode.valueOf(it) }.getOrNull() },
            usedModel = usedModel,
            isError = isError || pending,
            pending = false,
        )
    }
}

class RoomToolLogSink(private val db: JarvisDatabase, private val scope: CoroutineScope) : ToolLogSink {
    override fun record(entry: ToolLogEntry) {
        scope.launch(Dispatchers.IO) {
            db.toolLog().insert(ToolLogEntity(0, entry.tool, entry.ok, entry.errorCode, entry.durationMs, entry.atEpochMs, entry.summary))
            // Retención: 30 días
            db.toolLog().prune(entry.atEpochMs - 30L * 86_400_000)
        }
    }
}

class RoomNoteIndex(private val db: JarvisDatabase) : NoteIndexRepository {
    override suspend fun replaceNote(path: String, chunks: List<NoteChunk>) = io {
        db.notes().replaceNote(path, chunks.map {
            NoteChunkEntity(0, it.notePath, it.noteTitle, it.heading, it.text, it.tags.joinToString(" "), it.links.joinToString("|"), it.isPrivate, it.modifiedAt)
        })
    }

    override suspend fun removeNotesNotIn(paths: Set<String>) = io {
        db.notes().paths().filter { it !in paths }.forEach { db.notes().deleteNote(it) }
    }

    override suspend fun candidates(terms: List<String>, limit: Int): List<NoteChunk> = io {
        // Términos ya normalizados (sin tildes) → consulta FTS con prefijos: "cafe* OR ventas*"
        val match = terms.filter { it.matches(Regex("[a-z0-9ñ]{2,}")) }.take(12).joinToString(" OR ") { "$it*" }
        if (match.isBlank()) emptyList() else db.notes().search(match, limit).map {
            NoteChunk(it.notePath, it.noteTitle, it.heading, it.text, it.tags.split(' ').filter { t -> t.isNotBlank() }.toSet(),
                it.links.split('|').filter { l -> l.isNotBlank() }.toSet(), it.isPrivate, it.modifiedAt)
        }
    }

    override suspend fun stats(): NoteIndexStats = io {
        NoteIndexStats(db.notes().noteCount(), db.notes().chunkCount(), db.notes().privateNoteCount(), db.notes().lastModified())
    }

    suspend fun clear() = io { db.notes().clear() }
}
