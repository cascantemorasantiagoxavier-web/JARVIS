package com.example.jarvis.core.notes

import com.example.jarvis.core.memory.TextRanker
import com.example.jarvis.core.text.TextNormalizer

/** Fragmento indexado de una nota de Obsidian. */
data class NoteChunk(
    val notePath: String,
    val noteTitle: String,
    val heading: String,
    val text: String,
    val tags: Set<String>,
    val links: Set<String>,
    val isPrivate: Boolean,
    val modifiedAt: Long,
)

/** Resultado de búsqueda con su procedencia, listo para mostrar o enviar (acotado) al modelo. */
data class NoteSnippet(val notePath: String, val noteTitle: String, val heading: String, val excerpt: String, val score: Double) {
    val citation: String get() = "[[${noteTitle}]]" + if (heading.isNotBlank()) " › $heading" else ""
}

/**
 * Divide Markdown de Obsidian en fragmentos por encabezado, extrae etiquetas, enlaces
 * y metadatos de privacidad del frontmatter.
 */
object MarkdownChunker {
    private val heading = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
    private val inlineTag = Regex("(?<![\\w/&#])#([\\p{L}0-9_/-]{2,})")
    private val wikiLink = Regex("\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|[^\\]]*)?]]")

    data class Frontmatter(val values: Map<String, String>, val tags: Set<String>)

    fun parseFrontmatter(lines: List<String>): Pair<Frontmatter, Int> {
        if (lines.firstOrNull()?.trim() != "---") return Frontmatter(emptyMap(), emptySet()) to 0
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
        if (end < 0) return Frontmatter(emptyMap(), emptySet()) to 0
        val body = lines.subList(1, end + 1)
        val values = mutableMapOf<String, String>()
        val tags = mutableSetOf<String>()
        var currentListKey: String? = null
        for (line in body) {
            val item = Regex("^\\s*-\\s+(.+)$").find(line)
            if (item != null && currentListKey != null) {
                if (currentListKey == "tags" || currentListKey == "tag") tags += item.groupValues[1].trim().trim('"', '\'').removePrefix("#")
                continue
            }
            val kv = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(line) ?: continue
            val key = kv.groupValues[1].lowercase()
            val v = kv.groupValues[2].trim()
            currentListKey = if (v.isEmpty()) key else null
            values[key] = v
            if ((key == "tags" || key == "tag") && v.isNotEmpty()) {
                v.trim('[', ']').split(',', ' ').map { it.trim().trim('"', '\'').removePrefix("#") }.filter { it.isNotBlank() }.forEach { tags += it }
            }
        }
        return Frontmatter(values, tags) to end + 2
    }

    fun chunk(path: String, markdown: String, modifiedAt: Long, privacy: NotePrivacy, maxChunkChars: Int = 1200): List<NoteChunk> {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val (fm, start) = parseFrontmatter(lines)
        val content = lines.drop(start)
        val fileTitle = path.substringAfterLast('/').removeSuffix(".md")
        val title = fm.values["title"]?.trim('"', '\'')?.takeIf { it.isNotBlank() }
            ?: content.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: fileTitle

        val sections = mutableListOf<Pair<String, StringBuilder>>()
        val stack = ArrayList<String>()
        var current = StringBuilder()
        var currentHeading = ""
        var inCode = false
        val bodyTags = mutableSetOf<String>()
        val links = mutableSetOf<String>()
        for (line in content) {
            if (line.trimStart().startsWith("```")) inCode = !inCode
            val h = if (!inCode) heading.find(line) else null
            if (h != null) {
                if (current.isNotBlank()) sections += currentHeading to current
                val level = h.groupValues[1].length
                while (stack.size >= level) stack.removeAt(stack.size - 1)
                while (stack.size < level - 1) stack.add("")
                stack.add(h.groupValues[2].trim())
                currentHeading = stack.filter { it.isNotBlank() }.joinToString(" › ")
                current = StringBuilder()
                continue
            }
            if (!inCode) {
                inlineTag.findAll(line).forEach { bodyTags += it.groupValues[1] }
                wikiLink.findAll(line).forEach { links += it.groupValues[1].trim() }
            }
            current.appendLine(line)
        }
        if (current.isNotBlank()) sections += currentHeading to current

        val allTags = (fm.tags + bodyTags).map { it.lowercase() }.toSet()
        val isPrivate = privacy.isPrivate(path, fm.values, allTags)
        return sections.flatMap { (head, sb) -> split(sb.toString().trim(), maxChunkChars).map { head to it } }
            .filter { it.second.isNotBlank() }
            .map { (head, text) -> NoteChunk(path, title, head, text, allTags, links, isPrivate, modifiedAt) }
    }

    private fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (para in text.split(Regex("\n\\s*\n"))) {
            if (sb.length + para.length > max && sb.isNotEmpty()) {
                out += sb.toString().trim()
                sb.clear()
            }
            if (para.length > max) para.chunked(max).forEach { out += it } else sb.append(para).append("\n\n")
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }
}

/**
 * Reglas de privacidad: una nota es privada si su frontmatter lo indica
 * (`privado: true`, `private: true`, `jarvis: private|no`), si tiene la etiqueta #privado/#private
 * o si está en una carpeta excluida. Las notas privadas nunca se envían al modelo.
 */
class NotePrivacy(
    private val excludedFolders: List<String> = listOf("Privado", "Private", ".obsidian", ".trash"),
    private val privateTags: Set<String> = setOf("privado", "private", "confidencial", "no-jarvis"),
) {
    fun isPrivate(path: String, frontmatter: Map<String, String>, tags: Set<String>): Boolean {
        val fm = frontmatter.mapValues { TextNormalizer.fold(it.value.trim('"', '\'')) }
        if (fm["privado"] == "true" || fm["private"] == "true") return true
        if (fm["jarvis"] in setOf("private", "privado", "no", "false")) return true
        if (tags.any { it.lowercase() in privateTags }) return true
        val segments = path.split('/')
        return segments.dropLast(1).any { seg -> excludedFolders.any { it.equals(seg, ignoreCase = true) } }
    }
}

interface NoteIndexRepository {
    suspend fun replaceNote(path: String, chunks: List<NoteChunk>)
    suspend fun removeNotesNotIn(paths: Set<String>)
    /** Candidatos por coincidencia de términos (prefiltrado, p. ej. FTS); el ranking final se hace aquí. */
    suspend fun candidates(terms: List<String>, limit: Int): List<NoteChunk>
    suspend fun stats(): NoteIndexStats
}

data class NoteIndexStats(val notes: Int, val chunks: Int, val privateNotes: Int, val lastIndexedAt: Long?)

class NoteSearchService(private val index: NoteIndexRepository) {
    /**
     * Búsqueda selectiva: prefiltra candidatos, rankea, recorta extractos y aplica un presupuesto
     * de caracteres. Con `forModel = true` excluye notas privadas.
     */
    suspend fun search(query: String, limit: Int = 4, forModel: Boolean, maxCharsTotal: Int = 2400): List<NoteSnippet> {
        val terms = TextNormalizer.searchTokens(query).distinct()
        if (terms.isEmpty()) return emptyList()
        val candidates = index.candidates(terms, 200).filter { !forModel || !it.isPrivate }
        val docs = candidates.map { TextRanker.Doc(it, it.text, "${it.noteTitle} ${it.heading} ${it.tags.joinToString(" ")}") }
        val ranked = TextRanker.rank(query, docs, limit * 2)
        val out = mutableListOf<NoteSnippet>()
        var used = 0
        for ((chunk, score) in ranked) {
            if (out.size >= limit) break
            if (out.any { it.notePath == chunk.notePath && it.heading == chunk.heading }) continue
            val excerpt = excerpt(chunk.text, terms, 600)
            if (used + excerpt.length > maxCharsTotal) break
            used += excerpt.length
            out += NoteSnippet(chunk.notePath, chunk.noteTitle, chunk.heading, excerpt, score)
        }
        return out
    }

    internal fun excerpt(text: String, terms: List<String>, max: Int): String {
        if (text.length <= max) return text
        val folded = TextNormalizer.fold(text)
        val pos = terms.mapNotNull { t -> folded.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (pos - max / 3).coerceAtLeast(0)
        val end = (start + max).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() + if (end < text.length) "…" else ""
    }
}
