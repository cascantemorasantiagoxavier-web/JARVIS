package com.example.jarvis.core.tools.local

import com.example.jarvis.core.text.TextNormalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Formatos de fecha en español sin depender de los datos de localización del sistema. */
object SpanishDates {
    private val daysShort = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
    private val daysLong = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    private val monthsShort = listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
    private val monthsLong = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    /** "Lun, 29 Sep 2025" (formato de la pantalla principal). */
    fun headerDate(d: LocalDate): String = "${daysShort[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${monthsShort[d.monthValue - 1]} ${d.year}"

    /** "07:42" en 24 h. */
    fun clock(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /** "lunes 29 de septiembre de 2025". */
    fun longDate(d: LocalDate): String = "${daysLong[d.dayOfWeek.value - 1]} ${d.dayOfMonth} de ${monthsLong[d.monthValue - 1]} de ${d.year}"

    fun spokenTime(t: LocalTime): String {
        val h = t.hour
        val period = when (h) { in 0..5 -> "de la madrugada"; in 6..11 -> "de la mañana"; in 12..18 -> "de la tarde"; else -> "de la noche" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (t.minute == 0) "$h12 en punto $period" else "$h12:%02d $period".format(t.minute)
    }

    fun dueLabel(due: LocalDateTime, hasTime: Boolean, today: LocalDate): String {
        val date = due.toLocalDate()
        val day = when (date) {
            today -> "hoy"
            today.plusDays(1) -> "mañana"
            today.minusDays(1) -> "ayer"
            else -> "${daysLong[date.dayOfWeek.value - 1]} ${date.dayOfMonth} de ${monthsLong[date.monthValue - 1]}"
        }
        return if (hasTime) "$day a las ${clock(due.toLocalTime())}" else day
    }

    internal fun monthIndex(word: String): Int? {
        val w = TextNormalizer.fold(word)
        val i = monthsLong.indexOfFirst { TextNormalizer.fold(it) == w || TextNormalizer.fold(it).take(3) == w.take(3) && w.length >= 3 }
        return if (i >= 0) i + 1 else null
    }

    internal fun dayOfWeek(word: String): DayOfWeek? {
        val w = TextNormalizer.fold(word)
        val i = daysLong.indexOfFirst { TextNormalizer.fold(it) == w }
        return if (i >= 0) DayOfWeek.of(i + 1) else null
    }
}

/** Fecha/hora resuelta a partir de lenguaje natural. */
data class DueSpec(val dateTime: LocalDateTime, val hasTime: Boolean)

/**
 * Intérprete de fechas en español: "hoy", "mañana a las 3 pm", "pasado mañana", "el viernes a las 10",
 * "el 5 de noviembre", "5/11", "2026-11-05T09:30", "en 20 minutos", "en 2 horas", "esta noche", "al mediodía".
 */
object NaturalDateParser {
    fun parse(text: String, now: ZonedDateTime): DueSpec? {
        val raw = text.trim()
        runCatching { return DueSpec(LocalDateTime.parse(raw), true) }
        runCatching { return DueSpec(ZonedDateTime.parse(raw).withZoneSameInstant(now.zone).toLocalDateTime(), true) }
        runCatching { return DueSpec(LocalDate.parse(raw).atStartOfDay(), false) }

        // Sin tildes y con ñ→n: las reglas se escriben sin diacríticos ("manana").
        val s = TextNormalizer.fold(raw).replace('ñ', 'n')
        val today = now.toLocalDate()

        Regex("\\ben (\\d+|un|una|media) (minuto|minutos|hora|horas|dia|dias|semana|semanas)\\b").find(s)?.let { m ->
            val n = when (val q = m.groupValues[1]) { "un", "una" -> 1L; "media" -> 0L; else -> q.toLong() }
            val base = now.toLocalDateTime()
            val dt = when {
                m.groupValues[1] == "media" && m.groupValues[2].startsWith("hora") -> base.plusMinutes(30)
                m.groupValues[2].startsWith("minuto") -> base.plusMinutes(n)
                m.groupValues[2].startsWith("hora") -> base.plusHours(n)
                m.groupValues[2].startsWith("dia") -> base.plusDays(n)
                else -> base.plusWeeks(n)
            }
            val timed = m.groupValues[2].startsWith("minuto") || m.groupValues[2].startsWith("hora")
            return DueSpec(if (timed) dt.withSecond(0).withNano(0) else dt.toLocalDate().atStartOfDay(), timed)
        }

        // "por/de la mañana" y "esta mañana" son franjas horarias; cualquier otro "mañana" es el día siguiente.
        val withoutDayParts = s.replace(Regex("\\b((de|por) la|esta) manana\\b"), " ")
        var date: LocalDate? = when {
            Regex("\\bpasado manana\\b").containsMatchIn(s) -> today.plusDays(2)
            Regex("\\bmanana\\b").containsMatchIn(withoutDayParts) -> today.plusDays(1)
            Regex("\\b(hoy|esta noche|esta tarde|esta manana)\\b").containsMatchIn(s) -> today
            else -> null
        }

        Regex("\\b(?:el )?(lunes|martes|miercoles|jueves|viernes|sabado|domingo)\\b").find(s)?.let { m ->
            val dow = SpanishDates.dayOfWeek(m.groupValues[1])!!
            date = if (today.dayOfWeek == dow) today.plusWeeks(1) else today.with(TemporalAdjusters.next(dow))
        }
        Regex("\\b(\\d{1,2}) de ([a-z]+)(?: de (\\d{4}))?\\b").find(s)?.let { m ->
            val month = SpanishDates.monthIndex(m.groupValues[2])
            if (month != null) {
                val year = m.groupValues[3].toIntOrNull() ?: today.year
                var d = runCatching { LocalDate.of(year, month, m.groupValues[1].toInt()) }.getOrNull()
                if (d != null && m.groupValues[3].isEmpty() && d.isBefore(today)) d = d.plusYears(1)
                if (d != null) date = d
            }
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(s)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: today.year
            runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()?.let { date = it }
        }

        val time = parseTime(s)
        if (date == null && time == null) return null
        if (date == null) {
            val candidate = today.atTime(time)
            return DueSpec(if (candidate.isAfter(now.toLocalDateTime())) candidate else candidate.plusDays(1), true)
        }
        return if (time != null) DueSpec(date!!.atTime(time), true) else DueSpec(date!!.atStartOfDay(), false)
    }

    internal fun parseTime(s: String): LocalTime? {
        if (Regex("\\b(al )?mediodia\\b").containsMatchIn(s)) return LocalTime.NOON
        if (Regex("\\bmedianoche\\b").containsMatchIn(s)) return LocalTime.of(23, 59)
        val m = Regex("\\ba las? (\\d{1,2})(?::(\\d{2})| y (media|cuarto))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|de la manana|de la tarde|de la noche|de la madrugada)?").find(s)
            ?: Regex("\\b(\\d{1,2}):(\\d{2})\\s*(am|pm)?\\b").find(s)?.let { mm ->
                return buildTime(mm.groupValues[1].toInt(), mm.groupValues[2].toInt(), mm.groupValues[3])
            }
        if (m != null) {
            val minute = when (m.groupValues[3]) { "media" -> 30; "cuarto" -> 15; else -> m.groupValues[2].toIntOrNull() ?: 0 }
            return buildTime(m.groupValues[1].toInt(), minute, m.groupValues[4])
        }
        return when {
            Regex("\\besta noche\\b").containsMatchIn(s) -> LocalTime.of(20, 0)
            Regex("\\besta tarde\\b").containsMatchIn(s) -> LocalTime.of(15, 0)
            Regex("\\b((por|de) la|esta) manana\\b").containsMatchIn(s) -> LocalTime.of(9, 0)
            else -> null
        }
    }

    private fun buildTime(hourIn: Int, minute: Int, period: String): LocalTime? {
        if (hourIn > 23 || minute > 59) return null
        var h = hourIn
        when {
            period.startsWith("p") || period == "de la tarde" || period == "de la noche" -> if (h < 12) h += 12
            period.startsWith("a") || period == "de la manana" || period == "de la madrugada" -> if (h == 12) h = 0
            // Sin indicador: 1–6 se interpreta como tarde (uso habitual: "a las 3")
            period.isEmpty() && h in 1..6 -> h += 12
        }
        return LocalTime.of(h % 24, minute)
    }
}
