package com.example.jarvis.core.text

import java.text.Normalizer

object TextNormalizer {
    private val diacritics = Regex("\\p{Mn}+")
    private val wakePrefix = Regex("^(?:oye\\s+|hola\\s+|ok\\s+)?j\\.?\\s*a\\.?\\s*r\\.?\\s*v\\.?\\s*i\\.?\\s*s\\.?[\\s,:;.!¡¿?-]*", RegexOption.IGNORE_CASE)
    private val politeness = Regex("\\b(por favor|porfa|porfavor)\\b", RegexOption.IGNORE_CASE)

    /** Minúsculas y sin tildes, conservando la ñ. */
    fun fold(text: String): String {
        val protectedN = text.replace('ñ', '\u0001').replace('Ñ', '\u0002')
        val stripped = Normalizer.normalize(protectedN, Normalizer.Form.NFD).replace(diacritics, "")
        return stripped.replace('\u0001', 'ñ').replace('\u0002', 'ñ').lowercase()
    }

    /** Quita la palabra de activación ("JARVIS, ...") y fórmulas de cortesía. */
    fun stripWakeWord(text: String): String = text.trim().replace(wakePrefix, "").trim()

    /** Forma canónica para detección de intenciones. */
    fun canonical(text: String): String =
        fold(stripWakeWord(text))
            .replace(politeness, " ")
            .replace(Regex("[¿?¡!]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val stopwords = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al", "a", "en", "y", "o", "que", "por",
        "para", "con", "sin", "se", "su", "sus", "mi", "mis", "tu", "tus", "es", "son", "fue", "lo", "le", "les", "me",
        "te", "nos", "como", "mas", "pero", "si", "no", "ya", "muy", "este", "esta", "estos", "estas", "ese", "esa",
        "eso", "esto", "hay", "ser", "the", "and", "of", "to", "in", "is", "for", "on", "with",
    )

    /** Tokens para búsqueda: sin tildes, sin palabras vacías, con plural simple eliminado. */
    fun searchTokens(text: String): List<String> =
        fold(text).split(Regex("[^a-z0-9ñ]+"))
            .filter { it.length > 1 && it !in stopwords }
            .map(::stem)

    fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ces") -> word.dropLast(3) + "z"
        word.length > 4 && word.endsWith("es") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") -> word.dropLast(1)
        else -> word
    }
}
