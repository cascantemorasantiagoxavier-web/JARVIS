package com.example.jarvis.ai.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.jarvis.domain.memory.models.*
import org.json.JSONArray
import java.io.File

class JarvisSQLiteMemoryHelper : SQLiteOpenHelper {

    constructor(context: Context) : super(context, DATABASE_NAME, null, DATABASE_VERSION)
    constructor(dbFile: File) : super(null, dbFile.absolutePath, null, DATABASE_VERSION)

    companion object {
        private const val DATABASE_NAME = "jarvis_memory.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MEMORIES = "memories"

        private const val COL_ID = "id"
        private const val COL_CONTENT = "content"
        private const val COL_LEVEL = "level"
        private const val COL_IMPORTANCE = "importance"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_SOURCE = "source"
        private const val COL_CONFIDENCE = "confidence"
        private const val COL_TAGS = "tags"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_MEMORIES (
                $COL_ID TEXT PRIMARY KEY,
                $COL_CONTENT TEXT NOT NULL,
                $COL_LEVEL TEXT NOT NULL,
                $COL_IMPORTANCE REAL NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_CONFIDENCE REAL NOT NULL,
                $COL_TAGS TEXT NOT NULL
            )
        """
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMORIES")
        onCreate(db)
    }

    fun upsert(entry: MemoryEntry) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, entry.id)
            put(COL_CONTENT, entry.content)
            put(COL_LEVEL, entry.level.name)
            put(COL_IMPORTANCE, entry.importance)
            put(COL_TIMESTAMP, entry.timestamp)
            put(COL_SOURCE, entry.source)
            put(COL_CONFIDENCE, entry.confidence)
            put(COL_TAGS, JSONArray(entry.tags).toString())
        }
        db.insertWithOnConflict(TABLE_MEMORIES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(id: String): Boolean {
        val db = writableDatabase
        val rows = db.delete(TABLE_MEMORIES, "$COL_ID = ?", arrayOf(id))
        return rows > 0
    }

    fun get(id: String): MemoryEntry? {
        val db = readableDatabase
        val cursor = db.query(TABLE_MEMORIES, null, "$COL_ID = ?", arrayOf(id), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToEntry(it)
            }
        }
        return null
    }

    fun getAll(): List<MemoryEntry> {
        val list = mutableListOf<MemoryEntry>()
        val db = readableDatabase
        val cursor = db.query(TABLE_MEMORIES, null, null, null, null, null, "$COL_TIMESTAMP DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToEntry(it))
            }
        }
        return list
    }

    fun clear() {
        val db = writableDatabase
        db.delete(TABLE_MEMORIES, null, null)
    }

    private fun cursorToEntry(cursor: Cursor): MemoryEntry {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID))
        val content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT))
        val levelStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_LEVEL))
        val importance = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_IMPORTANCE))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
        val source = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE))
        val confidence = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_CONFIDENCE))
        val tagsStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAGS))

        val tagsList = mutableListOf<String>()
        try {
            val arr = JSONArray(tagsStr)
            for (i in 0 until arr.length()) {
                tagsList.add(arr.getString(i))
            }
        } catch (_: Exception) {}

        val level = try { MemoryLevel.valueOf(levelStr) } catch (_: Exception) { MemoryLevel.LONG_TERM }

        return MemoryEntry(
            id = id,
            content = content,
            level = level,
            importance = importance,
            timestamp = timestamp,
            source = source,
            confidence = confidence,
            tags = tagsList
        )
    }
}
