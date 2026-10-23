package com.example.jarvis.core.device

import com.example.jarvis.core.text.TextNormalizer

/** Resuelve "abre spotify" contra las apps instaladas (coincidencia por nombre visible y alias comunes). */
object AppMatcher {
    private val aliases = mapOf(
        "maps" to listOf("google maps", "mapas", "maps"),
        "youtube" to listOf("youtube", "yt"),
        "whatsapp" to listOf("whatsapp", "wasap", "guasap", "whats"),
        "x" to listOf("twitter", "x"),
        "play store" to listOf("play store", "tienda", "google play"),
        "gmail" to listOf("gmail", "correo"),
        "fotos" to listOf("fotos", "galeria", "photos", "gallery"),
        "chrome" to listOf("chrome", "navegador"),
        "tiktok" to listOf("tiktok", "tik tok"),
    )

    sealed interface Match {
        data class App(val app: InstalledApp) : Match
        data class Special(val kind: SpecialApp) : Match
        data class NotFound(val suggestions: List<String>) : Match
    }

    fun match(query: String, apps: List<InstalledApp>): Match {
        val q = TextNormalizer.fold(query).replace(Regex("^(la |el )?(app |aplicacion )?(de )?"), "").trim()
        if (q.isEmpty()) return Match.NotFound(emptyList())
        val folded = apps.map { it to TextNormalizer.fold(it.label) }
        folded.firstOrNull { it.second == q }?.let { return Match.App(it.first) }
        val expanded = aliases.entries.firstOrNull { (_, al) -> q in al }?.let { listOf(it.key) + it.value }.orEmpty()
        folded.firstOrNull { (_, l) -> expanded.any { it == l } }?.let { return Match.App(it.first) }
        SpecialApp.entries.firstOrNull { q in it.aliases }?.let { return Match.Special(it) }
        folded.firstOrNull { (_, l) -> l.startsWith(q) || q.length >= 4 && l.contains(q) }?.let { return Match.App(it.first) }
        val suggestions = folded.filter { (_, l) -> l.take(2) == q.take(2) }.map { it.first.label }.take(3)
        return Match.NotFound(suggestions)
    }
}
