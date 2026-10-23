package com.example.jarvis.core.memory

import com.example.jarvis.core.text.TextNormalizer
import java.time.Instant
import kotlin.math.ln

enum class MemoryKind(val label: String) {
    PREFERENCE("Preferencia"),
    FACT("Dato"),
    PROJECT("Proyecto"),
    CONTACT("Contacto"),
    OTHER("Otro"),
}

/**
 * Recuerdo de largo plazo (distinto del historial de mensajes).
 * `isPrivate`: nunca se envía al modelo remoto; solo se muestra localmente.
 */
data class MemoryItem(
    val id: Long = 0,
    val kind: MemoryKind,
    val content: String,
    val isPrivate: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val source: String = "usuario",
)

interface MemoryRepository {
    suspend fun insert(item: MemoryItem): Long
    suspend fun update(item: MemoryItem): Boolean
    suspend fun delete(id: Long): Boolean
    suspend fun get(id: Long): MemoryItem?
    suspend fun all(): List<MemoryItem>
}

sealed interface RememberResult {
    data class Saved(val item: MemoryItem) : RememberResult
    data class Duplicate(val existing: MemoryItem) : RememberResult
    data class Updated(val item: MemoryItem, val previous: String) : RememberResult
}

/** Ranking BM25 simplificado sobre tokens normalizados en español. */
object TextRanker {
    data class Doc<T>(val item: T, val text: String, val boostText: String = "")

    fun <T> rank(query: String, docs: List<Doc<T>>, limit: Int, minScore: Double = 0.01): List<Pair<T, Double>> {
        val q = TextNormalizer.searchTokens(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val tokenized = docs.map { TextNormalizer.searchTokens(it.text) to TextNormalizer.searchTokens(it.boostText).toSet() }
        val avgLen = tokenized.map { it.first.size }.average().coerceAtLeast(1.0)
        val df = q.associateWith { term -> tokenized.count { (toks, boost) -> term in toks || term in boost } }
        val n = docs.size.toDouble()
        val k1 = 1.2
        val b = 0.75
        return docs.indices.map { i ->
            val (toks, boost) = tokenized[i]
            var score = 0.0
            for (term in q) {
                val tf = toks.count { it == term }.toDouble() + if (term in boost) 2.0 else 0.0
                if (tf == 0.0) {
                    // Coincidencia parcial de prefijo (p. ej. "ferret" ~ "ferreteria")
                    if (term.length >= 5 && toks.any { it.startsWith(term) || term.startsWith(it) && it.length >= 5 }) score += 0.3
                    continue
                }
                val idf = ln(1 + (n - df.getValue(term) + 0.5) / (df.getValue(term) + 0.5))
                score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * toks.size / avgLen))
            }
            docs[i].item to score
        }.filter { it.second >= minScore }.sortedByDescending { it.second }.take(limit)
    }

    /** Similitud de Jaccard entre conjuntos de tokens (para detectar duplicados). */
    fun similarity(a: String, b: String): Double {
        val ta = TextNormalizer.searchTokens(a).toSet()
        val tb = TextNormalizer.searchTokens(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }
}

class MemoryService(private val repo: MemoryRepository, private val now: () -> Instant) {
    /**
     * Guarda un recuerdo. Si existe uno casi idéntico, no duplica. Si es una preferencia que
     * reformula otra muy parecida, la actualiza. Solo devuelve Saved/Updated tras escribir con éxito.
     */
    suspend fun remember(content: String, kind: MemoryKind = MemoryKind.FACT, isPrivate: Boolean = false, source: String = "usuario"): RememberResult {
        val clean = content.trim().removeSuffix(".").trim()
        require(clean.length >= 3) { "El recuerdo está vacío." }
        val all = repo.all()
        val norm = TextNormalizer.canonical(clean)
        all.firstOrNull { TextNormalizer.canonical(it.content) == norm || TextRanker.similarity(it.content, clean) >= 0.9 }
            ?.let { return RememberResult.Duplicate(it) }
        if (kind == MemoryKind.PREFERENCE) {
            val similar = all.filter { it.kind == MemoryKind.PREFERENCE }
                .maxByOrNull { TextRanker.similarity(it.content, clean) }
                ?.takeIf { TextRanker.similarity(it.content, clean) >= 0.6 }
            if (similar != null) {
                val updated = similar.copy(content = clean, updatedAt = now(), isPrivate = isPrivate || similar.isPrivate)
                check(repo.update(updated)) { "No se pudo actualizar el recuerdo." }
                return RememberResult.Updated(updated, similar.content)
            }
        }
        val item = MemoryItem(kind = kind, content = clean, isPrivate = isPrivate, createdAt = now(), source = source)
        val id = repo.insert(item)
        check(id > 0) { "No se pudo guardar el recuerdo." }
        return RememberResult.Saved(item.copy(id = id))
    }

    suspend fun recall(query: String, limit: Int = 5, includePrivate: Boolean = true): List<MemoryItem> {
        val docs = repo.all().filter { includePrivate || !it.isPrivate }
            .map { TextRanker.Doc(it, it.content, it.kind.label) }
        return TextRanker.rank(query, docs, limit).map { it.first }
    }

    suspend fun update(id: Long, content: String): MemoryItem? {
        val m = repo.get(id) ?: return null
        val updated = m.copy(content = content.trim(), updatedAt = now())
        return if (repo.update(updated)) updated else null
    }

    suspend fun forget(id: Long): Boolean = repo.delete(id)

    suspend fun all(): List<MemoryItem> = repo.all().sortedByDescending { it.updatedAt }

    /** Preferencias que pueden ir al modelo en cada turno (no privadas, acotadas). */
    suspend fun preferencesForModel(maxChars: Int = 600): List<MemoryItem> =
        repo.all().filter { it.kind == MemoryKind.PREFERENCE && !it.isPrivate }
            .sortedByDescending { it.updatedAt }
            .fold(mutableListOf<MemoryItem>() to 0) { (acc, used), m ->
                if (used + m.content.length <= maxChars) acc.add(m)
                acc to used + m.content.length
            }.first
}

/** Detecta órdenes explícitas de memoria en español: "recuerda que…", "olvida…". */
object MemoryCommands {
    data class RememberCommand(val content: String, val kind: MemoryKind, val isPrivate: Boolean)

    private val rememberRe = Regex("^(?:jarvis[, ]+)?(?:por favor )?(?:recuerda|recuerde|recordar|guarda en (?:tu )?memoria|anota en (?:tu )?memoria|memoriza|no olvides)(?: que| esto:?| lo siguiente:?)?\\s+(.+)$", RegexOption.IGNORE_CASE)

    fun parseRemember(text: String): RememberCommand? {
        val t = text.trim()
        val m = rememberRe.find(t) ?: return null
        var content = m.groupValues[1].trim()
        if (content.length < 3) return null
        val folded = TextNormalizer.fold(content)
        val isPrivate = Regex("\\b(es privado|en privado|privadamente|de forma privada|confidencial)\\b").containsMatchIn(folded)
        if (isPrivate) content = content.replace(Regex("(?i)[,(]?\\s*(es privado|en privado|de forma privada|confidencial)\\)?"), "").trim()
        val kind = when {
            Regex("\\b(prefiero|me gusta|no me gusta|quiero que|odio|me encanta|siempre|nunca)\\b").containsMatchIn(folded) -> MemoryKind.PREFERENCE
            Regex("\\b(proyecto|cliente|negocio|empresa|producto)\\b").containsMatchIn(folded) -> MemoryKind.PROJECT
            Regex("\\b(telefono|correo|numero de|se llama)\\b").containsMatchIn(folded) -> MemoryKind.CONTACT
            else -> MemoryKind.FACT
        }
        return RememberCommand(content.replaceFirstChar { it.uppercase() }, kind, isPrivate)
    }
}
