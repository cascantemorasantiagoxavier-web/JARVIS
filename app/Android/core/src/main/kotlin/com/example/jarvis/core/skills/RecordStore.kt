package com.example.jarvis.core.skills

import com.example.jarvis.core.json.JarvisJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class StoredRecord(val collection: String, val id: String, val json: String, val updatedAt: Long)

/**
 * Almacén local de datos estructurados de las habilidades (gastos, inventario, cotizaciones,
 * tarjetas de estudio…). Vive solo en el teléfono (Room en Android). Es independiente de la
 * bóveda de Obsidian: cada uno se actualiza por separado.
 */
interface RecordStore {
    suspend fun put(record: StoredRecord)
    suspend fun putAll(records: List<StoredRecord>)
    suspend fun get(collection: String, id: String): StoredRecord?
    suspend fun list(collection: String): List<StoredRecord>
    suspend fun delete(collection: String, id: String): Boolean
}

class InMemoryRecordStore : RecordStore {
    private val data = ConcurrentHashMap<String, ConcurrentHashMap<String, StoredRecord>>()
    override suspend fun put(record: StoredRecord) { data.getOrPut(record.collection) { ConcurrentHashMap() }[record.id] = record }
    override suspend fun putAll(records: List<StoredRecord>) = records.forEach { put(it) }
    override suspend fun get(collection: String, id: String) = data[collection]?.get(id)
    override suspend fun list(collection: String) = data[collection]?.values?.sortedBy { it.updatedAt }.orEmpty()
    override suspend fun delete(collection: String, id: String) = data[collection]?.remove(id) != null
}

/** Almacén en archivo JSON (pruebas de persistencia al cerrar y reabrir; herramientas de escritorio). */
class FileRecordStore(private val file: File) : RecordStore {
    @Serializable private data class Row(val c: String, val i: String, val j: String, val u: Long)
    private val mutex = Mutex()
    private val ser = ListSerializer(Row.serializer())

    private fun load(): MutableList<Row> = if (file.exists()) JarvisJson.decodeFromString(ser, file.readText()).toMutableList() else mutableListOf()
    private fun save(rows: List<Row>) { file.parentFile?.mkdirs(); file.writeText(JarvisJson.encodeToString(ser, rows)) }

    override suspend fun put(record: StoredRecord) = putAll(listOf(record))
    override suspend fun putAll(records: List<StoredRecord>) = mutex.withLock {
        val rows = load()
        for (r in records) {
            rows.removeAll { it.c == r.collection && it.i == r.id }
            rows += Row(r.collection, r.id, r.json, r.updatedAt)
        }
        save(rows)
    }
    override suspend fun get(collection: String, id: String) = mutex.withLock { load().firstOrNull { it.c == collection && it.i == id }?.let { StoredRecord(it.c, it.i, it.j, it.u) } }
    override suspend fun list(collection: String) = mutex.withLock { load().filter { it.c == collection }.sortedBy { it.u }.map { StoredRecord(it.c, it.i, it.j, it.u) } }
    override suspend fun delete(collection: String, id: String) = mutex.withLock {
        val rows = load()
        rows.removeAll { it.c == collection && it.i == id }.also { if (it) save(rows) }
    }
}

/** Vista tipada de una colección. Los ids son correlativos por colección ("1", "2", …). */
class TypedCollection<T>(
    private val store: RecordStore,
    val name: String,
    private val serializer: KSerializer<T>,
    private val clock: () -> Long,
) {
    suspend fun all(): List<Pair<String, T>> = store.list(name).mapNotNull { r ->
        runCatching { r.id to JarvisJson.decodeFromString(serializer, r.json) }.getOrNull()
    }

    suspend fun values(): List<T> = all().map { it.second }
    suspend fun get(id: String): T? = store.get(name, id)?.let { runCatching { JarvisJson.decodeFromString(serializer, it.json) }.getOrNull() }
    suspend fun nextId(): String = ((store.list(name).mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0) + 1).toString()
    fun record(id: String, value: T) = StoredRecord(name, id, JarvisJson.encodeToString(serializer, value), clock())
    suspend fun save(id: String, value: T) = store.put(record(id, value))
    suspend fun delete(id: String) = store.delete(name, id)
}
