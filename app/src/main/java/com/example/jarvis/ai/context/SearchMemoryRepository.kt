package com.example.jarvis.ai.context

import android.util.Log
import com.example.jarvis.ai.memory.PersistenceMemoryHolder
import com.example.jarvis.ai.memory.getOrNull
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import java.io.File

data class SearchMemoryEntry(
    val id: String,
    val query: String,
    val searchType: String,
    val summary: String,
    val businessResults: List<BusinessResultMemory> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

interface SearchMemoryRepository {
    suspend fun saveSearch(entry: SearchMemoryEntry)
    suspend fun getSearch(id: String): SearchMemoryEntry?
    suspend fun findRelevantSearches(query: String): List<SearchMemoryEntry>
    suspend fun deleteSearch(id: String)
    suspend fun clearSearches()
}

class DefaultSearchMemoryRepository(
    private val memoryHolder: PersistenceMemoryHolder? = null,
    private val storageDir: File? = null
) : SearchMemoryRepository {

    private val inMemoryEntries = mutableMapOf<String, SearchMemoryEntry>()

    init {
        loadPersistedEntries()
    }

    private fun safeLogW(msg: String) {
        try {
            Log.w("JARVIS_MEMORY", msg)
        } catch (_: Throwable) {
            println("[JARVIS_MEMORY_WARN] $msg")
        }
    }

    private fun safeLogE(msg: String) {
        try {
            Log.e("JARVIS_MEMORY", msg)
        } catch (_: Throwable) {
            println("[JARVIS_MEMORY_ERR] $msg")
        }
    }

    private fun loadPersistedEntries() {
        if (storageDir == null || !storageDir.exists()) return
        try {
            val files = storageDir.listFiles { _, name -> name.startsWith("search_") && name.endsWith(".json") }
            files?.forEach { file ->
                try {
                    val rawStr = file.readText(Charsets.UTF_8)
                    val entry = deserializeSearchMemoryEntry(rawStr)
                    if (entry != null) {
                        inMemoryEntries[entry.id] = entry
                    } else {
                        safeLogW("Corrupted search memory file skipped: ${file.name}")
                    }
                } catch (e: Exception) {
                    safeLogW("Error reading search memory file ${file.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            safeLogE("Error scanning search memory directory: ${e.message}")
        }
    }

    private fun persistFile(entry: SearchMemoryEntry) {
        if (storageDir == null) return
        try {
            if (!storageDir.exists()) storageDir.mkdirs()
            val file = File(storageDir, "search_${entry.id}.json")
            val jsonStr = serializeSearchMemoryEntry(entry)
            file.writeText(jsonStr, Charsets.UTF_8)
        } catch (e: Exception) {
            safeLogE("Error persisting search memory file for ${entry.id}: ${e.message}")
        }
    }

    private fun deleteFile(id: String) {
        if (storageDir == null) return
        try {
            val fileJson = File(storageDir, "search_${id}.json")
            if (fileJson.exists()) fileJson.delete()
        } catch (e: Exception) {
            safeLogE("Error deleting search memory file for $id: ${e.message}")
        }
    }

    override suspend fun saveSearch(entry: SearchMemoryEntry) {
        inMemoryEntries[entry.id] = entry
        persistFile(entry)

        try {
            memoryHolder?.repository?.save(
                MemoryEntry(
                    id = "search_${entry.id}",
                    content = serializeSearchMemoryEntry(entry),
                    level = MemoryLevel.LONG_TERM
                )
            )
        } catch (_: Exception) {}
    }

    override suspend fun getSearch(id: String): SearchMemoryEntry? {
        inMemoryEntries[id]?.let { return it }

        if (storageDir != null) {
            val fileJson = File(storageDir, "search_${id}.json")
            if (fileJson.exists()) {
                try {
                    val entry = deserializeSearchMemoryEntry(fileJson.readText(Charsets.UTF_8))
                    if (entry != null) {
                        inMemoryEntries[entry.id] = entry
                        return entry
                    }
                } catch (_: Exception) {}
            }
        }

        try {
            val appResult = memoryHolder?.repository?.get("search_$id")
            val memoryEntry = appResult?.getOrNull()
            if (memoryEntry != null) {
                val entry = deserializeSearchMemoryEntry(memoryEntry.content)
                if (entry != null) {
                    inMemoryEntries[entry.id] = entry
                    return entry
                }
            }
        } catch (_: Exception) {}

        return null
    }

    override suspend fun findRelevantSearches(query: String): List<SearchMemoryEntry> {
        val cleanQuery = query.lowercase().trim()
        val queryWords = cleanQuery.split(" ").filter { it.length > 2 }

        return inMemoryEntries.values.map { entry ->
            val entryQueryClean = entry.query.lowercase().trim()
            var score = 0.0f

            if (entryQueryClean == cleanQuery) {
                score += 10.0f
            } else if (entryQueryClean.contains(cleanQuery) || cleanQuery.contains(entryQueryClean)) {
                score += 5.0f
            }

            val matchedWords = queryWords.count { word -> entryQueryClean.contains(word) }
            score += matchedWords * 2.0f

            if (cleanQuery.contains("minisuper") || cleanQuery.contains("ferreteria") || cleanQuery.contains("restaurante")) {
                if (entry.searchType == "BUSINESS_SEARCH") score += 3.0f
            }

            val ageHours = (System.currentTimeMillis() - entry.timestamp) / 3600000.0f
            val recencyScore = (1.0f / (1.0f + ageHours)).coerceAtLeast(0.0f)
            score += recencyScore

            Pair(entry, score)
        }
        .filter { it.second > 0.0f }
        .sortedByDescending { it.second }
        .map { it.first }
    }

    override suspend fun deleteSearch(id: String) {
        inMemoryEntries.remove(id)
        deleteFile(id)
        try {
            memoryHolder?.repository?.delete("search_$id")
        } catch (_: Exception) {}
    }

    override suspend fun clearSearches() {
        inMemoryEntries.keys.toList().forEach { id ->
            deleteFile(id)
            try {
                memoryHolder?.repository?.delete("search_$id")
            } catch (_: Exception) {}
        }
        inMemoryEntries.clear()
    }

    companion object {
        fun jsonEscape(str: String): String {
            val sb = StringBuilder()
            for (c in str) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> {
                        if (c.code < 32) {
                            sb.append(String.format("\\u%04x", c.code))
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            return sb.toString()
        }

        fun serializeSearchMemoryEntry(entry: SearchMemoryEntry): String {
            val json = mutableMapOf<String, Any?>()
            json["id"] = entry.id
            json["query"] = entry.query
            json["searchType"] = entry.searchType
            json["summary"] = entry.summary
            json["timestamp"] = entry.timestamp
            json["sourceUrls"] = entry.sourceUrls

            val bizList = entry.businessResults.map { biz ->
                val map = mutableMapOf<String, Any?>()
                map["id"] = biz.id
                map["name"] = biz.name
                map["category"] = biz.category
                map["address"] = biz.address
                map["phone"] = biz.phone
                map["website"] = biz.website
                map["rating"] = biz.rating
                map["distanceKm"] = biz.distanceKm
                map["sourceUrl"] = biz.sourceUrl
                map["retrievedAt"] = biz.retrievedAt
                map
            }
            json["businessResults"] = bizList
            return toJsonString(json)
        }

        private fun toJsonString(obj: Any?): String {
            return when (obj) {
                null -> "null"
                is String -> "\"" + jsonEscape(obj) + "\""
                is Number, is Boolean -> obj.toString()
                is List<*> -> "[" + obj.joinToString(",") { toJsonString(it) } + "]"
                is Map<*, *> -> "{" + obj.entries.joinToString(",") { "\"" + jsonEscape(it.key.toString()) + "\":" + toJsonString(it.value) } + "}"
                else -> "\"" + jsonEscape(obj.toString()) + "\""
            }
        }

        class StrictJsonParser(private val input: String) {
            private var pos = 0

            fun parseRoot(): Any? {
                skipWhitespace()
                if (pos >= input.length) throw IllegalArgumentException("Empty JSON")
                val value = parseValue()
                skipWhitespace()
                if (pos < input.length) {
                    throw IllegalArgumentException("Unexpected trailing characters at pos $pos: ${input.substring(pos)}")
                }
                return value
            }

            fun parseValue(): Any? {
                skipWhitespace()
                if (pos >= input.length) throw IllegalArgumentException("Unexpected end of input")
                val c = input[pos]
                return when (c) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't', 'f' -> parseBoolean()
                    'n' -> parseNull()
                    in '0'..'9', '-' -> parseNumber()
                    else -> throw IllegalArgumentException("Unexpected character '$c' at pos $pos")
                }
            }

            private fun skipWhitespace() {
                while (pos < input.length && input[pos] <= ' ') {
                    pos++
                }
            }

            private fun parseObject(): Map<String, Any?> {
                val map = mutableMapOf<String, Any?>()
                pos++ // consume '{'
                skipWhitespace()
                if (pos < input.length && input[pos] == '}') {
                    pos++
                    return map
                }
                while (pos < input.length) {
                    skipWhitespace()
                    if (pos >= input.length || input[pos] != '"') {
                        throw IllegalArgumentException("Expected string key in object at pos $pos")
                    }
                    val key = parseString()
                    skipWhitespace()
                    if (pos >= input.length || input[pos] != ':') {
                        throw IllegalArgumentException("Expected ':' after key at pos $pos")
                    }
                    pos++ // consume ':'
                    skipWhitespace()
                    val value = parseValue()
                    map[key] = value
                    skipWhitespace()
                    if (pos >= input.length) {
                        throw IllegalArgumentException("Unterminated object")
                    }
                    val next = input[pos]
                    if (next == '}') {
                        pos++
                        break
                    } else if (next == ',') {
                        pos++
                        skipWhitespace()
                        if (pos < input.length && input[pos] == '}') {
                            throw IllegalArgumentException("Trailing comma not allowed in JSON object")
                        }
                    } else {
                        throw IllegalArgumentException("Expected ',' or '}' in object at pos $pos, found '$next'")
                    }
                }
                return map
            }

            private fun parseArray(): List<Any?> {
                val list = mutableListOf<Any?>()
                pos++ // consume '['
                skipWhitespace()
                if (pos < input.length && input[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < input.length) {
                    skipWhitespace()
                    if (pos < input.length && input[pos] == ']') {
                        pos++
                        break
                    }
                    val value = parseValue()
                    list.add(value)
                    skipWhitespace()
                    if (pos >= input.length) {
                        throw IllegalArgumentException("Unterminated array")
                    }
                    val next = input[pos]
                    if (next == ']') {
                        pos++
                        break
                    } else if (next == ',') {
                        pos++
                        skipWhitespace()
                        if (pos < input.length && input[pos] == ']') {
                            throw IllegalArgumentException("Trailing comma not allowed in JSON array")
                        }
                    } else {
                        throw IllegalArgumentException("Expected ',' or ']' in array at pos $pos, found '$next'")
                    }
                }
                return list
            }

            private fun parseString(): String {
                if (pos >= input.length || input[pos] != '"') {
                    throw IllegalArgumentException("Expected opening quote at pos $pos")
                }
                pos++ // consume opening '"'
                val sb = StringBuilder()
                while (pos < input.length) {
                    val c = input[pos]
                    if (c == '"') {
                        pos++
                        return sb.toString()
                    } else if (c == '\\') {
                        pos++
                        if (pos >= input.length) throw IllegalArgumentException("Unterminated escape sequence")
                        val esc = input[pos]
                        when (esc) {
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'u' -> {
                                if (pos + 4 < input.length) {
                                    val hex = input.substring(pos + 1, pos + 5)
                                    try {
                                        sb.append(hex.toInt(16).toChar())
                                        pos += 4
                                    } catch (_: Exception) {
                                        throw IllegalArgumentException("Invalid unicode escape \\u$hex")
                                    }
                                } else {
                                    throw IllegalArgumentException("Truncated unicode escape")
                                }
                            }
                            else -> throw IllegalArgumentException("Invalid escape sequence \\$esc")
                        }
                    } else {
                        if (c.code < 32) {
                            throw IllegalArgumentException("Unescaped control character in string")
                        }
                        sb.append(c)
                    }
                    pos++
                }
                throw IllegalArgumentException("Unterminated string missing closing quote")
            }

            private fun parseBoolean(): Boolean {
                if (input.startsWith("true", pos)) {
                    pos += 4
                    return true
                } else if (input.startsWith("false", pos)) {
                    pos += 5
                    return false
                } else {
                    throw IllegalArgumentException("Invalid boolean at pos $pos")
                }
            }

            private fun parseNull(): Nothing? {
                if (input.startsWith("null", pos)) {
                    pos += 4
                    return null
                } else {
                    throw IllegalArgumentException("Invalid null literal at pos $pos")
                }
            }

            private fun parseNumber(): Number {
                skipWhitespace()
                val start = pos
                if (pos < input.length && input[pos] == '-') {
                    pos++
                }
                if (pos >= input.length || input[pos] !in '0'..'9') {
                    throw IllegalArgumentException("Invalid number format at pos $start")
                }
                if (input[start] == '0' && pos < input.length && input[pos] in '0'..'9') {
                    throw IllegalArgumentException("Leading zeros not allowed in JSON numbers")
                }
                while (pos < input.length && (input[pos] in '0'..'9' || input[pos] == '.' || input[pos] == 'e' || input[pos] == 'E' || input[pos] == '+' || input[pos] == '-')) {
                    pos++
                }
                val numStr = input.substring(start, pos)
                if (numStr.endsWith(".") || numStr.endsWith("e") || numStr.endsWith("E")) {
                    throw IllegalArgumentException("Malformed number ending: $numStr")
                }
                return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                    numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid floating point number: $numStr")
                } else {
                    numStr.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer number: $numStr")
                }
            }
        }

        fun deserializeSearchMemoryEntry(raw: String): SearchMemoryEntry? {
            return try {
                if (raw.isBlank() || !raw.trim().startsWith("{")) return null
                val parser = StrictJsonParser(raw)
                val map = parser.parseRoot() as? Map<*, *> ?: return null

                val id = map["id"]?.toString() ?: return null
                val query = map["query"]?.toString() ?: ""
                val searchType = map["searchType"]?.toString() ?: ""
                val summary = map["summary"]?.toString() ?: ""
                val timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()

                val sourceUrls = (map["sourceUrls"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

                val businessResults = mutableListOf<BusinessResultMemory>()
                val bizList = map["businessResults"] as? List<*>
                bizList?.forEach { item ->
                    val bMap = item as? Map<*, *>
                    if (bMap != null) {
                        val bId = bMap["id"]?.toString() ?: "biz_0"
                        val bName = bMap["name"]?.toString() ?: ""
                        val bCat = bMap["category"]?.toString() ?: "negocio"
                        val bAddr = bMap["address"]?.toString() ?: ""
                        val bPhone = bMap["phone"]?.toString() ?: ""
                        val bWeb = bMap["website"]?.toString() ?: ""

                        val rawRating = bMap["rating"]
                        val rating = when (rawRating) {
                            is Number -> rawRating.toFloat()
                            else -> null
                        }

                        val rawDist = bMap["distanceKm"]
                        val distanceKm = when (rawDist) {
                            is Number -> rawDist.toFloat()
                            else -> null
                        }

                        val bSrc = bMap["sourceUrl"]?.toString() ?: ""
                        val bTime = (bMap["retrievedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

                        businessResults.add(
                            BusinessResultMemory(
                                id = bId,
                                name = bName,
                                category = bCat,
                                address = bAddr,
                                phone = bPhone,
                                website = bWeb,
                                rating = rating,
                                distanceKm = distanceKm,
                                sourceUrl = bSrc,
                                retrievedAt = bTime
                            )
                        )
                    }
                }

                SearchMemoryEntry(
                    id = id,
                    query = query,
                    searchType = searchType,
                    summary = summary,
                    businessResults = businessResults,
                    sourceUrls = sourceUrls,
                    timestamp = timestamp
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
