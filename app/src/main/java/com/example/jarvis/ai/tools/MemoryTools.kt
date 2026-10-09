package com.example.jarvis.ai.tools

import com.example.jarvis.ai.agents.normalizeQuery
import com.example.jarvis.ai.memory.DefaultMemoryPolicy
import com.example.jarvis.ai.memory.PersistenceMemoryHolder
import com.example.jarvis.ai.memory.PersistentMemoryRepository
import com.example.jarvis.ai.memory.PersonalityManager
import com.example.jarvis.ai.memory.getOrNull
import com.example.jarvis.domain.memory.MemoryRepository
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryPolicyDecision
import com.example.jarvis.domain.memory.models.MemoryQuery
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class MemorySaveTool(
    private val repository: MemoryRepository = PersistenceMemoryHolder.repository,
    private val policy: DefaultMemoryPolicy = DefaultMemoryPolicy()
) : Tool {
    override val name = "memory_save"
    override val description = "Guarda una preferencia o dato relevante del usuario de forma permanente"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val rawInput = params["query"]?.toString() ?: params["input"]?.toString() ?: params["title"]?.toString() ?: ""
        val cleanInput = rawInput.replace("recuerda que ", "", ignoreCase = true)
            .replace("guarda que ", "", ignoreCase = true)
            .replace("memoriza que ", "", ignoreCase = true)
            .replace("no olvides que ", "", ignoreCase = true)
            .replace("recuerda ", "", ignoreCase = true)
            .trim()

        if (cleanInput.isBlank()) {
            return "Por favor, especifica el dato o preferencia que deseas que recuerde."
        }

        val lower = cleanInput.lowercase()
        val sensitiveKeywords = listOf("religión", "religion", "orientación sexual", "orientacion sexual", "diagnóstico", "enfermedad", "partido político", "afiliación política")
        if (sensitiveKeywords.any { lower.contains(it) } && !lower.contains("guarda explícitamente")) {
            return "Por privacidad, no almaceno automáticamente datos personales sensibles."
        }

        val existingEntries = repository.search(MemoryQuery(query = "")).getOrNull()?.entries ?: emptyList()
        val decision = policy.evaluate(
            candidateContent = cleanInput,
            candidateLevel = MemoryLevel.LONG_TERM,
            candidateSource = "user_explicit",
            candidateImportance = 0.8f,
            existingEntries = existingEntries
        )

        return when (decision.decision) {
            MemoryPolicyDecision.SAVE -> {
                val newEntry = decision.targetEntry ?: MemoryEntry(content = cleanInput)
                repository.save(newEntry)
                "Hecho. Guardé esa preferencia en mi memoria permanente."
            }
            MemoryPolicyDecision.UPDATE -> {
                val updated = decision.targetEntry ?: MemoryEntry(content = cleanInput)
                repository.update(updated)
                "Entendido. He actualizado esa preferencia en mi memoria."
            }
            MemoryPolicyDecision.IGNORE -> {
                "Ese dato ya está registrado en mi memoria."
            }
            MemoryPolicyDecision.DELETE_REVIEW -> {
                "La eliminación de memoria requiere confirmación."
            }
        }
    }
}

class MemorySearchTool(
    private val repository: MemoryRepository = PersistenceMemoryHolder.repository
) : Tool {
    override val name = "memory_search"
    override val description = "Consulta los recuerdos y preferencias guardados del usuario"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: ""
        val cleanQuery = query.normalizeQuery().trim()

        val searchTerm = when {
            cleanQuery.contains("como me llamo") || cleanQuery.contains("cual es mi nombre") || cleanQuery.contains("mi nombre") -> "llamo"
            cleanQuery.contains("donde vivo") || cleanQuery.contains("cual es mi direccion") -> "vivo"
            else -> cleanQuery
        }

        var result = repository.search(MemoryQuery(query = searchTerm, limit = 10))
        var entries = result.getOrNull()?.entries ?: emptyList()

        if (entries.isEmpty() && searchTerm.isNotBlank()) {
            result = repository.search(MemoryQuery(query = "", limit = 10))
            entries = result.getOrNull()?.entries ?: emptyList()
        }

        if (entries.isEmpty()) {
            return "No tengo ese dato o preferencia guardada en mi memoria."
        }

        val formatted = entries.joinToString("\n") { "- ${it.content}" }
        return "Esto es lo que tengo guardado en mi memoria permanente:\n$formatted"
    }
}

class MemoryDeleteTool(
    private val repository: MemoryRepository = PersistenceMemoryHolder.repository
) : Tool {
    override val name = "memory_delete"
    override val description = "Elimina o borra un recuerdo o preferencia específica de la memoria"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: params["input"]?.toString() ?: ""
        val cleanQuery = query.replace("olvida que ", "", ignoreCase = true)
            .replace("borra el recuerdo de ", "", ignoreCase = true)
            .replace("borra el recuerdo ", "", ignoreCase = true)
            .replace("borra ", "", ignoreCase = true)
            .replace("elimina mi preferencia sobre ", "", ignoreCase = true)
            .replace("elimina mi preferencia ", "", ignoreCase = true)
            .replace("elimina ", "", ignoreCase = true)
            .trim()

        if (cleanQuery.isBlank() || cleanQuery.contains("toda mi memoria")) {
            return "Para borrar toda tu memoria de forma masiva se requiere confirmación explícita."
        }

        val searchRes = repository.search(MemoryQuery(query = cleanQuery, limit = 5)).getOrNull()?.entries ?: emptyList()
        if (searchRes.isEmpty()) {
            return "No encontré ningún recuerdo guardado que coincida con '$cleanQuery'."
        }

        val target = searchRes.first()
        repository.delete(target.id)
        return "Listo. Eliminé ese recuerdo de mi memoria."
    }
}

class MemoryClearAllTool(
    private val repository: MemoryRepository = PersistenceMemoryHolder.repository
) : Tool {
    override val name = "memory_clear_all"
    override val description = "Borra toda la memoria permanente de JARVIS (Acción de alto riesgo/requiere confirmación)"
    override val riskLevel = RiskLevel.DESTRUCTIVE

    override suspend fun execute(params: Map<String, Any>): String {
        val confirmed = params["confirmed"]?.toString()?.toBoolean() ?: false
        if (!confirmed) {
            return "Esto eliminará todos los recuerdos persistentes. ¿Quieres continuar?"
        }
        (repository as? PersistentMemoryRepository)?.clearAll()
        return "Se ha borrado toda la memoria permanente de JARVIS."
    }
}

class PreferenceUpdateTool(
    private val personalityManager: PersonalityManager = PersistenceMemoryHolder.personalityManager
) : Tool {
    override val name = "preference_update"
    override val description = "Actualiza el estilo de respuesta y personalidad de JARVIS"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val command = params["query"]?.toString() ?: params["command"]?.toString() ?: ""
        val success = personalityManager.updateFromNaturalCommand(command)

        return if (success) {
            "Entendido. He actualizado tu preferencia de respuesta y estilo de interacción."
        } else {
            "No pude interpretar el cambio de preferencia. Puedes pedirme respuestas más cortas, más detalladas o más técnicas."
        }
    }
}
