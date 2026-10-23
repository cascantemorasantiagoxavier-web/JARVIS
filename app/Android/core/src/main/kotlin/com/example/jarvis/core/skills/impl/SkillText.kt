package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.memory.TextRanker
import com.example.jarvis.core.text.TextNormalizer
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Utilidades de texto en español compartidas por las habilidades (sin red ni modelo). */
object SkillText {
    private val sentenceSplit = Regex("(?<=[.!?…])\\s+(?=[¿¡\"“(]?[A-ZÁÉÍÓÚÑ0-9])")

    fun sentences(text: String): List<String> =
        text.replace(Regex("\\s+"), " ").trim().split(sentenceSplit).map { it.trim() }.filter { it.length > 1 }

    fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*").findAll(text).map { it.value }.toList()

    /** Sílabas aproximadas en español (grupos vocálicos, con diptongos comunes). */
    fun syllables(word: String): Int {
        val w = TextNormalizer.fold(word).replace(Regex("[^a-zñü]"), "")
        if (w.isEmpty()) return 0
        val groups = Regex("[aeiouyü]+").findAll(w).sumOf { m ->
            // Hiato entre vocales fuertes (a, e, o): cada una cuenta como sílaba.
            val strong = m.value.count { it in "aeo" }
            maxOf(1, strong)
        }
        return maxOf(1, groups)
    }

    /** Índice de Fernández-Huerta (adaptación de Flesch al español): mayor = más fácil. */
    fun readability(text: String): Double? {
        val w = words(text)
        val s = sentences(text).size.coerceAtLeast(1)
        if (w.size < 10) return null
        val p = w.sumOf { syllables(it) } * 100.0 / w.size
        val f = s * 100.0 / w.size
        return (206.84 - 0.60 * p - 1.02 * f).coerceIn(0.0, 100.0)
    }

    fun readabilityLabel(score: Double?): String = when {
        score == null -> "texto demasiado corto para medir"
        score >= 80 -> "muy fácil"
        score >= 70 -> "fácil"
        score >= 60 -> "normal"
        score >= 50 -> "algo difícil"
        score >= 30 -> "difícil"
        else -> "muy difícil"
    }

    /** Resumen extractivo: frases más representativas en su orden original. */
    fun extractiveSummary(text: String, maxSentences: Int, focus: String? = null): List<String> {
        val ss = sentences(text).filter { words(it).size >= 4 }
        if (ss.size <= maxSentences) return ss
        val query = focus?.takeIf { it.isNotBlank() } ?: keyTerms(text, 12).joinToString(" ")
        val ranked = TextRanker.rank(query, ss.mapIndexed { i, s -> TextRanker.Doc(i, s) }, maxSentences, minScore = 0.0).map { it.first }
        val picked = ranked.ifEmpty { ss.indices.take(maxSentences) }
        return picked.sorted().map { ss[it] }
    }

    /** Términos más frecuentes (sin palabras vacías). */
    fun keyTerms(text: String, limit: Int): List<String> {
        val counts = mutableMapOf<String, Int>()
        val shown = mutableMapOf<String, String>()
        for (w in words(text)) {
            val t = TextNormalizer.searchTokens(w).firstOrNull() ?: continue
            if (t.length < 4 || t.all { it.isDigit() }) continue
            counts[t] = (counts[t] ?: 0) + 1
            shown.putIfAbsent(t, w.lowercase())
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit).map { shown.getValue(it.key) }
    }

    /**
     * Contenido de una orden con dos puntos o tras una frase clave:
     * "corrige este texto: hola mundo" → "hola mundo".
     */
    fun afterCue(text: String, cues: List<String>): String? {
        val colon = text.indexOf(':')
        if (colon in 1 until text.length - 1) {
            val head = TextNormalizer.fold(text.substring(0, colon))
            if (cues.any { head.contains(it) }) return text.substring(colon + 1).trim().takeIf { it.isNotEmpty() }
        }
        val folded = TextNormalizer.fold(text)
        for (c in cues) {
            val i = folded.indexOf(c)
            if (i >= 0) return text.substring(i + c.length).trim().trimStart(',', ':', '-').trim().takeIf { it.length >= 2 }
        }
        return null
    }

    fun containsAny(text: String, vararg cues: String): Boolean {
        val f = TextNormalizer.fold(text)
        return cues.any { f.contains(it) }
    }

    fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    /** Nombre de archivo seguro a partir de un título. */
    fun fileName(title: String, ext: String): String {
        val base = TextNormalizer.fold(title).replace(Regex("[^a-z0-9ñ]+"), "-").trim('-').take(50).ifEmpty { "documento" }
        return "$base.$ext"
    }
}

/** Importe con moneda. Nunca se convierte entre monedas sin una fuente de tipo de cambio. */
data class Money(val amount: BigDecimal, val currency: String) {
    fun format(): String = Money.format(amount, currency)

    companion object {
        private val symbols = DecimalFormatSymbols(Locale.forLanguageTag("es-CR")).apply { groupingSeparator = ' '; decimalSeparator = ',' }

        fun format(amount: BigDecimal, currency: String): String {
            val df = DecimalFormat("#,##0.##", symbols)
            val n = df.format(amount.setScale(2, RoundingMode.HALF_UP))
            return when (currency) {
                "CRC" -> "₡$n"
                "USD" -> "US$$n"
                "EUR" -> "€$n"
                else -> "$n $currency"
            }
        }

        private val pattern = Regex(
            "(?i)(₡|¢|US\\$|USD|\\$|€|EUR|CRC)?\\s?(\\d{1,3}(?:[ .,]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)\\s?(mil(?:es)?|millones|millón|k)?\\s?(colones|colón|₡|dólares|dolares|dólar|dolar|usd|euros|euro|eur|crc)?",
        )

        /** Interpreta importes en texto en español: "₡5 000", "5000 colones", "$20", "20 dólares", "3 mil colones". */
        fun parse(text: String, defaultCurrency: String? = null): Money? = findAll(text, defaultCurrency).firstOrNull()

        fun findAll(text: String, defaultCurrency: String? = null): List<Money> = pattern.findAll(text).mapNotNull { m ->
            val pre = m.groupValues[1].lowercase()
            val post = m.groupValues[4].lowercase()
            val currency = when {
                pre in setOf("₡", "¢", "crc") || post.startsWith("col") || post == "crc" || post == "₡" -> "CRC"
                pre in setOf("us$", "usd", "$") || post.startsWith("d") || post == "usd" -> "USD"
                pre in setOf("€", "eur") || post.startsWith("eur") -> "EUR"
                else -> defaultCurrency
            } ?: return@mapNotNull null
            var n = number(m.groupValues[2]) ?: return@mapNotNull null
            when (m.groupValues[3].lowercase()) {
                "mil", "miles", "k" -> n = n.multiply(BigDecimal(1000))
                "millones", "millón" -> n = n.multiply(BigDecimal(1_000_000))
            }
            if (n.signum() <= 0) null else Money(n, currency)
        }.toList()

        /** "1.234,56" / "1,234.56" / "1 234" / "12,5" → número. */
        fun number(raw: String): BigDecimal? {
            val s = raw.trim().replace(" ", "")
            val lastDot = s.lastIndexOf('.')
            val lastComma = s.lastIndexOf(',')
            val normalized = when {
                lastDot >= 0 && lastComma >= 0 -> if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
                lastComma >= 0 -> if (s.length - lastComma - 1 == 3 && s.count { it == ',' } >= 1 && s.length > 4) s.replace(",", "") else s.replace(',', '.')
                lastDot >= 0 -> if (s.length - lastDot - 1 == 3 && s.length > 4) s.replace(".", "") else s
                else -> s
            }
            return normalized.toBigDecimalOrNull()
        }
    }
}
