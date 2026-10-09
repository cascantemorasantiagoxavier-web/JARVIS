package com.example.jarvis.ai.memory

import android.content.Context
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.core.util.JsonUtils
import com.example.jarvis.domain.memory.MemoryRepository
import com.example.jarvis.domain.memory.models.*
import java.io.File

class PersistentMemoryRepository(
    private val context: Context? = null,
    private val testDbFile: File? = null
) : MemoryRepository {

    private val storage = mutableMapOf<String, MemoryEntry>()
    private val fallbackFile: File? by lazy {
        try {
            when {
                testDbFile != null -> testDbFile
                context != null -> File(context.filesDir, "memory/persistent_memories.dat")
                else -> File(System.getProperty("java.io.tmpdir"), "jarvis_persistent_memories.dat")
            }
        } catch (_: Exception) {
            null
        }
    }

    init {
        loadFromPersistence()
    }

    private fun loadFromPersistence() {
        try {
            val file = fallbackFile
            if (file != null && file.exists()) {
                val rawStr = file.readText(Charsets.UTF_8)
                val entries = deserializeEntries(rawStr)
                for (entry in entries) {
                    storage[entry.id] = entry
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveToPersistence() {
        try {
            val file = fallbackFile
            if (file != null) {
                if (file.parentFile != null && !file.parentFile!!.exists()) {
                    file.parentFile!!.mkdirs()
                }
                val rawStr = serializeEntries(storage.values.toList())
                file.writeText(rawStr, Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }

    override suspend fun save(entry: MemoryEntry): AppResult<MemoryEntry> {
        storage[entry.id] = entry
        saveToPersistence()
        return AppResult.Success(entry)
    }

    override suspend fun update(entry: MemoryEntry): AppResult<MemoryEntry> {
        if (!storage.containsKey(entry.id)) {
            return AppResult.Error(IllegalArgumentException("Memory entry with ID ${entry.id} not found"))
        }
        storage[entry.id] = entry
        saveToPersistence()
        return AppResult.Success(entry)
    }

    override suspend fun delete(id: String): AppResult<Boolean> {
        val removed = storage.remove(id) != null
        if (removed) {
            saveToPersistence()
        }
        return AppResult.Success(removed)
    }

    override suspend fun get(id: String): AppResult<MemoryEntry?> {
        return AppResult.Success(storage[id])
    }

    override suspend fun search(query: MemoryQuery): AppResult<MemoryResult> {
        val filtered = storage.values.filter { entry ->
            val matchesLevel = query.targetLevels.contains(entry.level)
            val matchesImportance = entry.importance >= query.minImportance
            val matchesQuery = query.query.isBlank() ||
                    entry.content.contains(query.query, ignoreCase = true) ||
                    entry.tags.any { it.contains(query.query, ignoreCase = true) }
            val matchesTags = query.tags.isEmpty() || entry.tags.any { query.tags.contains(it) }

            matchesLevel && matchesImportance && matchesQuery && matchesTags
        }.take(query.limit)

        return AppResult.Success(
            MemoryResult(
                query = query.query,
                entries = filtered,
                snippets = filtered.map { it.content },
                totalCount = filtered.size,
                relevanceScore = if (filtered.isNotEmpty()) 0.9f else 0.0f
            )
        )
    }

    override suspend fun listRelevant(input: String, limit: Int): AppResult<List<MemoryEntry>> {
        val queryResult = search(MemoryQuery(query = input, limit = limit))
        return when (queryResult) {
            is AppResult.Success -> AppResult.Success(queryResult.data.entries)
            is AppResult.Error -> AppResult.Error(queryResult.exception, queryResult.message)
            is AppResult.Loading -> AppResult.Success(emptyList())
        }
    }

    fun clearAll(): Boolean {
        storage.clear()
        try {
            fallbackFile?.delete()
        } catch (_: Exception) {}
        return true
    }

    companion object {
        private fun serializeEntries(entries: List<MemoryEntry>): String {
            return entries.joinToString("|||") { entry ->
                val tagsStr = entry.tags.joinToString(",") { JsonUtils.escape(it) }
                listOf(
                    JsonUtils.escape(entry.id),
                    JsonUtils.escape(entry.content),
                    entry.level.name,
                    entry.importance.toString(),
                    entry.timestamp.toString(),
                    JsonUtils.escape(entry.source),
                    entry.confidence.toString(),
                    tagsStr
                ).joinToString("~~~")
            }
        }

        private fun deserializeEntries(raw: String): List<MemoryEntry> {
            val list = mutableListOf<MemoryEntry>()
            if (raw.isBlank()) return list
            try {
                val records = raw.split("|||")
                for (rec in records) {
                    if (rec.isBlank()) continue
                    val fields = rec.split("~~~")
                    if (fields.size >= 8) {
                        val id = JsonUtils.unescape(fields[0])
                        val content = JsonUtils.unescape(fields[1])
                        val level = try { MemoryLevel.valueOf(fields[2]) } catch (_: Exception) { MemoryLevel.LONG_TERM }
                        val importance = fields[3].toFloatOrNull() ?: 0.5f
                        val timestamp = fields[4].toLongOrNull() ?: System.currentTimeMillis()
                        val source = JsonUtils.unescape(fields[5])
                        val confidence = fields[6].toFloatOrNull() ?: 1.0f
                        val tags = fields[7].split(",").mapNotNull { if (it.isNotBlank()) JsonUtils.unescape(it) else null }

                        list.add(
                            MemoryEntry(
                                id = id,
                                content = content,
                                level = level,
                                importance = importance,
                                timestamp = timestamp,
                                source = source,
                                confidence = confidence,
                                tags = tags
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
            return list
        }
    }
}
