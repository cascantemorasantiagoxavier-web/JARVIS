package com.example.jarvis.core.skills.impl

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ColumnStats(
    val name: String,
    val type: String,
    val missing: Int,
    val distinct: Int,
    val sum: Double? = null,
    val mean: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val median: Double? = null,
    val top: List<Pair<String, Int>> = emptyList(),
    val firstDate: String? = null,
    val lastDate: String? = null,
)

data class GroupTotal(val key: String, val sum: Double, val count: Int)

/** CSV/TSV y estadísticas descriptivas sin dependencias. Los números aceptan formato es-CR y en. */
object Tabular {
    const val MAX_ROWS = 50_000

    fun parse(text: String): Table {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf('\t', ';', ',', '|').maxByOrNull { d -> firstLine.count { it == d } }?.takeIf { d -> firstLine.count { it == d } > 0 } ?: ','
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                quoted && c == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row += field.toString().trim(); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                    row += field.toString().trim(); field = StringBuilder()
                    if (row.any { it.isNotEmpty() }) records += row
                    row = mutableListOf()
                    if (records.size > MAX_ROWS) break
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString().trim(); if (row.any { it.isNotEmpty() }) records += row }
        if (records.isEmpty()) return Table(emptyList(), emptyList())
        val width = records.maxOf { it.size }
        val headers = records.first().let { h -> List(width) { idx -> h.getOrNull(idx)?.ifBlank { null } ?: "col${idx + 1}" } }
        return Table(headers, records.drop(1).map { r -> List(width) { r.getOrNull(it) ?: "" } })
    }

    fun toCsv(t: Table): String {
        fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        return (listOf(t.headers) + t.rows).joinToString("\n") { r -> r.joinToString(",") { esc(it) } }
    }

    fun number(raw: String): Double? {
        val s = raw.trim().removePrefix("₡").removePrefix("$").removePrefix("US$").removeSuffix("%").trim()
        if (s.isEmpty() || !Regex("^-?[\\d.,\\s]+$").matches(s)) return null
        return Money.number(s.replace(" ", ""))?.toDouble()
    }

    private val dateFormats = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy").map { DateTimeFormatter.ofPattern(it) }
    fun date(raw: String): LocalDate? = dateFormats.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(raw.trim().take(10), f) }.getOrNull() }

    fun stats(t: Table): List<ColumnStats> = t.headers.indices.map { c ->
        val values = t.rows.map { it.getOrElse(c) { "" } }
        val present = values.filter { it.isNotBlank() }
        val nums = present.mapNotNull { number(it) }
        val dates = if (nums.size < present.size * 0.8) present.mapNotNull { date(it) } else emptyList()
        when {
            present.isNotEmpty() && nums.size >= present.size * 0.8 -> {
                val sorted = nums.sorted()
                ColumnStats(
                    t.headers[c], "número", values.size - present.size, present.toSet().size,
                    sum = round(nums.sum()), mean = round(nums.average()), min = sorted.first(), max = sorted.last(),
                    median = round(if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2),
                )
            }
            present.isNotEmpty() && dates.size >= present.size * 0.8 ->
                ColumnStats(t.headers[c], "fecha", values.size - present.size, present.toSet().size, firstDate = dates.minOrNull().toString(), lastDate = dates.maxOrNull().toString())
            else -> ColumnStats(
                t.headers[c], "texto", values.size - present.size, present.toSet().size,
                top = present.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            )
        }
    }

    fun groupSum(t: Table, by: String, column: String): List<GroupTotal>? {
        val bi = findColumn(t, by) ?: return null
        val ci = findColumn(t, column) ?: return null
        return t.rows.groupBy { it.getOrElse(bi) { "" }.ifBlank { "(vacío)" } }
            .map { (k, rows) -> GroupTotal(k, round(rows.sumOf { number(it.getOrElse(ci) { "" }) ?: 0.0 }), rows.size) }
            .sortedByDescending { it.sum }
    }

    fun findColumn(t: Table, name: String): Int? {
        val n = com.example.jarvis.core.text.TextNormalizer.fold(name.trim())
        return t.headers.indexOfFirst { com.example.jarvis.core.text.TextNormalizer.fold(it) == n }.takeIf { it >= 0 }
            ?: t.headers.indexOfFirst { com.example.jarvis.core.text.TextNormalizer.fold(it).contains(n) }.takeIf { it >= 0 }
    }

    fun duplicates(t: Table): Int = t.rows.size - t.rows.toSet().size

    fun round(d: Double): Double = BigDecimal(d).round(MathContext(12)).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toDouble()
}
