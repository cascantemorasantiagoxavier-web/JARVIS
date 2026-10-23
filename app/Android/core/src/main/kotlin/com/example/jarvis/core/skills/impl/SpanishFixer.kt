package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.text.TextNormalizer

data class TextIssue(val type: String, val message: String, val excerpt: String? = null)

/**
 * Correcciones automáticas seguras para español (solo cambios inequívocos) y observaciones
 * de estilo. No sustituye a un corrector completo: lo que no es seguro se señala, no se cambia.
 */
object SpanishFixer {
    /** Errores frecuentes con una única corrección válida. */
    private val replacements = linkedMapOf(
        "haiga" to "haya", "nadien" to "nadie", "hize" to "hice", "osea" to "o sea", "aveces" to "a veces",
        "talvez" to "tal vez", "atravez" to "a través", "atraves" to "a través", "enserio" to "en serio",
        "porfavor" to "por favor", "dijistes" to "dijiste", "fuistes" to "fuiste", "vinistes" to "viniste",
        "tuvistes" to "tuviste", "hicistes" to "hiciste", "estubo" to "estuvo", "tambien" to "también",
        "despues" to "después", "aqui" to "aquí", "ademas" to "además", "asi" to "así", "dia" to "día", "dias" to "días",
        "telefono" to "teléfono", "rapido" to "rápido", "facil" to "fácil", "dificil" to "difícil", "proximo" to "próximo",
        "ojala" to "ojalá", "jamas" to "jamás", "quizas" to "quizás", "mas sin embargo" to "sin embargo",
        "en base a" to "con base en", "a groso modo" to "grosso modo", "haber si" to "a ver si", "xq" to "porque", "tmb" to "también",
    )

    fun fix(text: String): Pair<String, List<TextIssue>> {
        val issues = mutableListOf<TextIssue>()
        var t = text

        val doubleSpaces = Regex(" {2,}").findAll(t).count()
        if (doubleSpaces > 0) { t = t.replace(Regex(" {2,}"), " "); issues += TextIssue("espacios", "Se quitaron $doubleSpaces espacios dobles.") }

        val beforePunct = Regex(" +([,.;:?!)])").findAll(t).count()
        if (beforePunct > 0) { t = t.replace(Regex(" +([,.;:?!)])"), "$1"); issues += TextIssue("puntuación", "Se quitaron $beforePunct espacios antes de signos de puntuación.") }

        val missingAfter = Regex("([,;:])(\\p{L})").findAll(t).count()
        if (missingAfter > 0) { t = t.replace(Regex("([,;:])(\\p{L})"), "$1 $2"); issues += TextIssue("puntuación", "Se añadió espacio después de $missingAfter comas o puntos y coma.") }

        for ((wrong, right) in replacements) {
            val re = Regex("(?i)(?<![\\p{L}])${Regex.escape(wrong)}(?![\\p{L}])")
            val found = re.findAll(t).toList()
            if (found.isNotEmpty()) {
                t = re.replace(t) { m -> matchCase(m.value, right) }
                issues += TextIssue("ortografía", "«${found.first().value}» → «$right»" + if (found.size > 1) " (${found.size} veces)" else "", found.first().value)
            }
        }
        // Palabras agudas terminadas en -ion llevan tilde (nación, decisión, reunión, camión);
        // excepciones de la RAE (monosílabos ortográficos): guion, ion, prion.
        val ion = Regex("(?i)(?<![\\p{L}])(\\p{L}{2,})ion(?![\\p{L}])")
        val exceptions = setOf("guion", "prion", "truhan")
        val ionFound = ion.findAll(t).filter { TextNormalizer.fold(it.value) !in exceptions }.toList()
        if (ionFound.isNotEmpty()) {
            t = ion.replace(t) { m -> if (TextNormalizer.fold(m.value) in exceptions) m.value else m.groupValues[1] + matchCase(m.value.takeLast(3), "ión") }
            issues += TextIssue("ortografía", "Tilde en palabras terminadas en «-ión»: " + ionFound.take(3).joinToString { it.value }, ionFound.first().value)
        }

        val (opened, nOpen) = addOpeningMarks(t)
        if (nOpen > 0) { t = opened; issues += TextIssue("signos", "Se añadieron $nOpen signos de apertura (¿ ¡), obligatorios en español.") }

        val (capped, nCap) = capitalizeSentences(t)
        if (nCap > 0) { t = capped; issues += TextIssue("mayúsculas", "Se pusieron $nCap mayúsculas al inicio de oración.") }

        val repeated = Regex("([!?.]){3,}").findAll(t).count()
        if (repeated > 0) issues += TextIssue("estilo", "Hay $repeated grupos de signos repetidos (!!!, ???); en textos formales usa uno.")
        return t.trim() to issues
    }

    fun observations(text: String): List<TextIssue> {
        val out = mutableListOf<TextIssue>()
        SkillText.sentences(text).filter { SkillText.words(it).size > 30 }.take(3).forEach {
            out += TextIssue("frase larga", "Frase de ${SkillText.words(it).size} palabras; considera dividirla.", SkillText.truncate(it, 80))
        }
        val counts = SkillText.words(text).map { TextNormalizer.fold(it) }.filter { it.length >= 5 }.groupingBy { it }.eachCount()
        counts.filter { it.value >= 3 }.entries.sortedByDescending { it.value }.take(3).forEach {
            out += TextIssue("repetición", "«${it.key}» aparece ${it.value} veces; busca sinónimos.", it.key)
        }
        val passive = Regex("(?i)\\b(fue|fueron|ha sido|han sido|será|serán)\\s+\\p{L}+(ado|ada|ados|adas|ido|ida|idos|idas)\\b").findAll(text).toList()
        if (passive.size >= 2) out += TextIssue("estilo", "Hay ${passive.size} construcciones pasivas; la voz activa suele ser más clara.", passive.first().value)
        val mente = SkillText.sentences(text).firstOrNull { s -> Regex("\\p{L}+mente\\b").findAll(s).count() >= 3 }
        if (mente != null) out += TextIssue("estilo", "Varios adverbios en «-mente» en la misma frase.", SkillText.truncate(mente, 80))
        return out
    }

    private fun matchCase(original: String, replacement: String): String = when {
        original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
        original.firstOrNull()?.isUpperCase() == true -> replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }

    /** Añade ¿ y ¡ al inicio de la oración interrogativa o exclamativa que no los tiene. */
    private fun addOpeningMarks(text: String): Pair<String, Int> {
        val sb = StringBuilder(text)
        var added = 0
        var i = 0
        while (i < sb.length) {
            val c = sb[i]
            if (c == '?' || c == '!') {
                val open = if (c == '?') '¿' else '¡'
                var start = i - 1
                while (start >= 0 && sb[start] !in ".?!¿¡\n:" && !(sb[start] == '!' || sb[start] == '?')) start--
                val segment = sb.substring(start + 1, i)
                if (open !in segment && segment.isNotBlank() && segment.any { it.isLetter() }) {
                    var insertAt = start + 1
                    while (insertAt < i && sb[insertAt].isWhitespace()) insertAt++
                    sb.insert(insertAt, open)
                    added++
                    i++
                }
            }
            i++
        }
        return sb.toString() to added
    }

    private fun capitalizeSentences(text: String): Pair<String, Int> {
        val sb = StringBuilder(text)
        var n = 0
        var atStart = true
        for (i in sb.indices) {
            val c = sb[i]
            if (atStart && c.isLetter()) {
                if (c.isLowerCase()) { sb.setCharAt(i, c.uppercaseChar()); n++ }
                atStart = false
            } else if (c == '.' || c == '?' || c == '!' || c == '\n') {
                // No tras abreviaturas de una letra ni números con decimales.
                val prevWord = sb.substring(0, i).takeLastWhile { it.isLetterOrDigit() }
                atStart = !(c == '.' && (prevWord.length == 1 || prevWord.all { it.isDigit() } && i + 1 < sb.length && sb[i + 1].isDigit()))
            } else if (atStart && (c == '¿' || c == '¡' || c == '"' || c == '“' || c == '(' || c.isWhitespace())) {
                continue
            } else if (atStart && !c.isLetter()) {
                atStart = false
            }
        }
        return sb.toString() to n
    }
}
