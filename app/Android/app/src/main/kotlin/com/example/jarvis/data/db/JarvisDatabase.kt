package com.example.jarvis.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---------- Entidades ----------

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val content: String,
    val isPrivate: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String,
    val priority: String,
    /** Fecha/hora local ISO-8601 (sin zona), o null. */
    val due: String?,
    val hasTime: Boolean,
    val status: String,
    val remind: Boolean,
    val createdAt: Long,
    val completedAt: Long?,
)

@Entity(tableName = "prospects", indices = [Index(value = ["businessId"], unique = true)])
data class ProspectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val businessId: String,
    val name: String,
    val category: String?,
    val address: String?,
    val province: String?,
    val canton: String?,
    val district: String?,
    val phone: String?,
    val website: String?,
    val email: String?,
    val lat: Double?,
    val lon: Double?,
    val locationApproximate: Boolean,
    val sourceName: String,
    val sourceUrl: String?,
    val foundAt: Long,
    val status: String,
    val notes: String,
    val nextAction: String?,
    val updatedAt: Long,
    val snapshotJson: String,
)

@Entity(tableName = "chat_entries")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val at: Long,
    val source: String,
    val mode: String?,
    val usedModel: Boolean,
    val isError: Boolean,
    val pending: Boolean,
    /** Tarjetas serializadas (negocios, web, info) para restaurarlas tras reiniciar. */
    val cardsJson: String?,
    val toolRunsJson: String?,
)

@Entity(tableName = "tool_log")
data class ToolLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tool: String,
    val ok: Boolean,
    val errorCode: String?,
    val durationMs: Long,
    val at: Long,
    /** Resumen breve, sin argumentos ni contenido privado. */
    val summary: String,
)

@Entity(tableName = "note_chunks", indices = [Index("notePath")])
data class NoteChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notePath: String,
    val noteTitle: String,
    val heading: String,
    val text: String,
    val tags: String,
    val links: String,
    val isPrivate: Boolean,
    val modifiedAt: Long,
)

/** Índice de texto completo sin tildes (unicode61 remove_diacritics) sobre los fragmentos de notas. */
@Fts4(contentEntity = NoteChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=1"])
@Entity(tableName = "note_chunks_fts")
data class NoteChunkFts(
    val noteTitle: String,
    val heading: String,
    val text: String,
    val tags: String,
)

// ---------- DAOs ----------

@Dao
interface MemoryDao {
    @Insert fun insert(e: MemoryEntity): Long
    @Update fun update(e: MemoryEntity): Int
    @Query("DELETE FROM memories WHERE id = :id") fun delete(id: Long): Int
    @Query("SELECT * FROM memories WHERE id = :id") fun get(id: Long): MemoryEntity?
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun all(): List<MemoryEntity>
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun observe(): Flow<List<MemoryEntity>>
}

@Dao
interface TaskDao {
    @Insert fun insert(e: TaskEntity): Long
    @Update fun update(e: TaskEntity): Int
    @Query("DELETE FROM tasks WHERE id = :id") fun delete(id: Long): Int
    @Query("SELECT * FROM tasks WHERE id = :id") fun get(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks") fun all(): List<TaskEntity>
    @Query("SELECT * FROM tasks ORDER BY status, due") fun observe(): Flow<List<TaskEntity>>
}

@Dao
interface ProspectDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insert(e: ProspectEntity): Long
    @Update fun update(e: ProspectEntity): Int
    @Query("DELETE FROM prospects WHERE id = :id") fun delete(id: Long): Int
    @Query("SELECT * FROM prospects WHERE id = :id") fun get(id: Long): ProspectEntity?
    @Query("SELECT * FROM prospects WHERE businessId = :businessId LIMIT 1") fun byBusiness(businessId: String): ProspectEntity?
    @Query("SELECT * FROM prospects ORDER BY updatedAt DESC") fun all(): List<ProspectEntity>
    @Query("SELECT * FROM prospects ORDER BY updatedAt DESC") fun observe(): Flow<List<ProspectEntity>>
}

@Dao
interface ChatDao {
    @Insert fun insert(e: ChatEntity): Long
    @Update fun update(e: ChatEntity): Int
    @Query("SELECT * FROM (SELECT * FROM chat_entries ORDER BY id DESC LIMIT :limit) ORDER BY id ASC") fun recent(limit: Int): List<ChatEntity>
    @Query("DELETE FROM chat_entries") fun clear()
}

@Dao
interface ToolLogDao {
    @Insert fun insert(e: ToolLogEntity): Long
    @Query("SELECT * FROM tool_log ORDER BY at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<ToolLogEntity>>
    @Query("DELETE FROM tool_log WHERE at < :before") fun prune(before: Long): Int
}

@Dao
abstract class NoteDao {
    @Insert abstract fun insertAll(chunks: List<NoteChunkEntity>)
    @Query("DELETE FROM note_chunks WHERE notePath = :path") abstract fun deleteNote(path: String)
    @Query("SELECT DISTINCT notePath FROM note_chunks") abstract fun paths(): List<String>

    @Transaction
    open fun replaceNote(path: String, chunks: List<NoteChunkEntity>) {
        deleteNote(path)
        insertAll(chunks)
    }

    @Query(
        "SELECT note_chunks.* FROM note_chunks JOIN note_chunks_fts ON note_chunks.id = note_chunks_fts.rowid " +
            "WHERE note_chunks_fts MATCH :match LIMIT :limit",
    )
    abstract fun search(match: String, limit: Int): List<NoteChunkEntity>

    @Query("SELECT COUNT(DISTINCT notePath) FROM note_chunks") abstract fun noteCount(): Int
    @Query("SELECT COUNT(*) FROM note_chunks") abstract fun chunkCount(): Int
    @Query("SELECT COUNT(DISTINCT notePath) FROM note_chunks WHERE isPrivate = 1") abstract fun privateNoteCount(): Int
    @Query("SELECT MAX(modifiedAt) FROM note_chunks") abstract fun lastModified(): Long?
    @Query("DELETE FROM note_chunks") abstract fun clear()
    @Query("SELECT notePath, MAX(modifiedAt) AS modifiedAt FROM note_chunks GROUP BY notePath") abstract fun stamps(): List<NoteStamp>
}

/** Última modificación indexada de cada nota (para reindexar solo lo que cambió). */
data class NoteStamp(val notePath: String, val modifiedAt: Long)

@Database(
    entities = [
        MemoryEntity::class, TaskEntity::class, ProspectEntity::class, ChatEntity::class,
        ToolLogEntity::class, NoteChunkEntity::class, NoteChunkFts::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun tasks(): TaskDao
    abstract fun prospects(): ProspectDao
    abstract fun chat(): ChatDao
    abstract fun toolLog(): ToolLogDao
    abstract fun notes(): NoteDao

    companion object {
        const val NAME = "jarvis.db"

        fun build(context: Context, name: String? = NAME): JarvisDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            } else {
                Room.databaseBuilder(context, JarvisDatabase::class.java, name)
            }
            return builder.build()
        }
    }
}
