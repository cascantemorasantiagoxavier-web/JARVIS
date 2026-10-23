package com.example.jarvis.core.skills.impl

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Tabla extraída de un documento (CSV, TSV, hoja de cálculo). */
data class Table(val headers: List<String>, val rows: List<List<String>>)

data class ExtractedDocument(val name: String, val kind: String, val text: String, val table: Table? = null)

class UnsupportedDocumentException(message: String) : Exception(message)

/**
 * Extracción de texto sin librerías externas: TXT, Markdown, CSV/TSV, JSON, HTML, DOCX, ODT y XLSX
 * (primera hoja). PDF no está soportado: necesitaría una librería que no se incluye.
 */
object DocumentExtractor {
    const val MAX_BYTES = 8_000_000
    const val MAX_CHARS = 400_000

    val supportedMimeTypes = listOf(
        "text/plain", "text/markdown", "text/csv", "text/tab-separated-values", "text/html", "application/json",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.text",
    )

    fun extract(name: String, mimeType: String, bytes: ByteArray): ExtractedDocument {
        if (bytes.size > MAX_BYTES) throw UnsupportedDocumentException("El archivo supera ${MAX_BYTES / 1_000_000} MB.")
        val lower = name.lowercase()
        val kind = when {
            lower.endsWith(".pdf") || mimeType == "application/pdf" -> throw UnsupportedDocumentException("Los PDF todavía no se pueden leer en el teléfono. Conviértelo a DOCX o texto, o envía una foto de la página (visual_scene_assistant).")
            lower.endsWith(".docx") || mimeType.contains("wordprocessingml") -> "docx"
            lower.endsWith(".xlsx") || mimeType.contains("spreadsheetml") -> "xlsx"
            lower.endsWith(".odt") || mimeType.contains("opendocument.text") -> "odt"
            lower.endsWith(".csv") || mimeType == "text/csv" -> "csv"
            lower.endsWith(".tsv") || mimeType == "text/tab-separated-values" -> "tsv"
            lower.endsWith(".html") || lower.endsWith(".htm") || mimeType == "text/html" -> "html"
            lower.endsWith(".json") || mimeType == "application/json" -> "json"
            lower.endsWith(".md") || mimeType == "text/markdown" -> "markdown"
            mimeType.startsWith("text/") || lower.endsWith(".txt") -> "text"
            lower.endsWith(".doc") || lower.endsWith(".xls") -> throw UnsupportedDocumentException("Los formatos antiguos .doc/.xls no están soportados; guárdalo como .docx/.xlsx.")
            else -> throw UnsupportedDocumentException("Formato no soportado ($mimeType).")
        }
        return when (kind) {
            "docx" -> ExtractedDocument(name, kind, docx(bytes))
            "odt" -> ExtractedDocument(name, kind, odt(bytes))
            "xlsx" -> xlsx(bytes).let { t -> ExtractedDocument(name, kind, Tabular.toCsv(t), t) }
            "csv", "tsv" -> decode(bytes).let { s -> ExtractedDocument(name, kind, s, Tabular.parse(s)) }
            "html" -> ExtractedDocument(name, kind, html(decode(bytes)))
            else -> ExtractedDocument(name, kind, decode(bytes))
        }.let { it.copy(text = it.text.take(MAX_CHARS)) }
    }

    fun decode(bytes: ByteArray): String {
        val s = String(bytes, Charsets.UTF_8)
        // Archivos Windows-1252 frecuentes en hojas exportadas: si hay caracteres de reemplazo, reintentar.
        return if (s.contains('�')) String(bytes, Charsets.ISO_8859_1) else s.removePrefix("﻿")
    }

    fun html(source: String): String = source
        .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .let(::entities)
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()

    private fun entities(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    private fun zipEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && wanted(e.name)) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total > 40_000_000) throw UnsupportedDocumentException("El documento comprimido es demasiado grande.")
                    out[e.name] = String(data, Charsets.UTF_8)
                }
            }
        }
        if (out.isEmpty()) throw UnsupportedDocumentException("El archivo no tiene el contenido esperado (¿está dañado?).")
        return out
    }

    fun docx(bytes: ByteArray): String {
        val xml = zipEntries(bytes) { it == "word/document.xml" }.values.first()
        return xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t").replace(Regex("<w:br/>"), "\n")
            .replace(Regex("<[^>]+>"), "").let(::entities).replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    fun odt(bytes: ByteArray): String {
        val xml = zipEntries(bytes) { it == "content.xml" }.values.first()
        return xml.replace(Regex("</text:p>|</text:h>"), "\n").replace(Regex("<[^>]+>"), "").let(::entities).trim()
    }

    /** Primera hoja de un XLSX como tabla (cadenas compartidas y valores en línea). */
    fun xlsx(bytes: ByteArray): Table {
        val files = zipEntries(bytes) { it == "xl/sharedStrings.xml" || it == "xl/worksheets/sheet1.xml" }
        val shared = files["xl/sharedStrings.xml"]?.let { xml ->
            Regex("(?s)<si>(.*?)</si>").findAll(xml).map { si -> Regex("(?s)<t[^>]*>(.*?)</t>").findAll(si.groupValues[1]).joinToString("") { entities(it.groupValues[1]) } }.toList()
        }.orEmpty()
        val sheet = files["xl/worksheets/sheet1.xml"] ?: throw UnsupportedDocumentException("El libro no tiene hoja 1.")
        val rows = mutableListOf<MutableMap<Int, String>>()
        for (row in Regex("(?s)<row[^>]*>(.*?)</row>").findAll(sheet)) {
            val cells = mutableMapOf<Int, String>()
            for (c in Regex("(?s)<c ([^>]*?)(?:/>|>(.*?)</c>)").findAll(row.groupValues[1])) {
                val attrs = c.groupValues[1]
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1) ?: continue
                val type = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                val inner = c.groupValues[2]
                val value = when (type) {
                    "s" -> Regex("<v>(\\d+)</v>").find(inner)?.groupValues?.get(1)?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> Regex("(?s)<t[^>]*>(.*?)</t>").find(inner)?.groupValues?.get(1)?.let(::entities)
                    else -> Regex("<v>(.*?)</v>").find(inner)?.groupValues?.get(1)
                } ?: ""
                cells[column(ref)] = value
            }
            rows += cells
        }
        if (rows.isEmpty()) return Table(emptyList(), emptyList())
        val width = rows.maxOf { (it.keys.maxOrNull() ?: -1) + 1 }
        val grid = rows.map { r -> List(width) { r[it] ?: "" } }
        return Table(grid.first().mapIndexed { i, h -> h.ifBlank { "col${i + 1}" } }, grid.drop(1).filter { r -> r.any { it.isNotBlank() } })
    }

    private fun column(letters: String): Int = letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
}
