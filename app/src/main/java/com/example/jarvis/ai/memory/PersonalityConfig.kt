package com.example.jarvis.ai.memory

import android.content.Context
import android.content.SharedPreferences

data class PersonalityConfig(
    val verbosity: String = "balanced", // "short", "detailed", "direct", "balanced"
    val formality: String = "formal",   // "formal", "casual"
    val technicality: String = "adaptive", // "high", "standard", "adaptive"
    val language: String = "es",
    val useEmojis: Boolean = false
)

class PersonalityManager(private val context: Context? = null) {
    private val prefs: SharedPreferences? by lazy {
        try {
            context?.getSharedPreferences("jarvis_personality_config", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    private var currentConfig = PersonalityConfig()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        prefs?.let { p ->
            currentConfig = PersonalityConfig(
                verbosity = p.getString("verbosity", "balanced") ?: "balanced",
                formality = p.getString("formality", "formal") ?: "formal",
                technicality = p.getString("technicality", "adaptive") ?: "adaptive",
                language = p.getString("language", "es") ?: "es",
                useEmojis = p.getBoolean("useEmojis", false)
            )
        }
    }

    fun getConfig(): PersonalityConfig = currentConfig

    fun updateConfig(newConfig: PersonalityConfig) {
        currentConfig = newConfig
        prefs?.let { p ->
            p.edit()
                .putString("verbosity", newConfig.verbosity)
                .putString("formality", newConfig.formality)
                .putString("technicality", newConfig.technicality)
                .putString("language", newConfig.language)
                .putBoolean("useEmojis", newConfig.useEmojis)
                .apply()
        }
    }

    fun updateFromNaturalCommand(command: String): Boolean {
        val lower = command.lowercase().trim()
        var updated = false
        var config = currentConfig

        if (lower.contains("más directo") || lower.contains("mas directo") || lower.contains("respuestas directas")) {
            config = config.copy(verbosity = "direct")
            updated = true
        } else if (lower.contains("respuestas cortas") || lower.contains("más corto") || lower.contains("mas corto") || lower.contains("breve")) {
            config = config.copy(verbosity = "short")
            updated = true
        } else if (lower.contains("respuestas detalladas") || lower.contains("más detalle") || lower.contains("mas detalle") || lower.contains("explicación larga")) {
            config = config.copy(verbosity = "detailed")
            updated = true
        }

        if (lower.contains("más técnico") || lower.contains("mas técnico") || lower.contains("técnico") || lower.contains("tecnico")) {
            config = config.copy(technicality = "high")
            updated = true
        }

        if (lower.contains("formal")) {
            config = config.copy(formality = "formal")
            updated = true
        } else if (lower.contains("casual") || lower.contains("informal")) {
            config = config.copy(formality = "casual")
            updated = true
        }

        if (updated) {
            updateConfig(config)
        }
        return updated
    }
}
