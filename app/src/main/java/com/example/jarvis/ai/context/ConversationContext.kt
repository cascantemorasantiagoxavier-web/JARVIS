package com.example.jarvis.ai.context

import com.example.jarvis.ai.agents.normalizeQuery

data class ConversationEntity(
    val name: String,
    val type: String = "general",
    val value: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class ConversationTurn(
    val turnId: Int,
    val userInput: String,
    val intentType: String,
    val toolUsed: String? = null,
    val toolResult: String? = null,
    val assistantOutput: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ConversationContext {
    val turns = mutableListOf<ConversationTurn>()
    var activeEntity: ConversationEntity? = null
    var activeTopic: String? = null
    var lastToolUsed: String? = null
    var lastToolResult: String? = null
    val lastBusinessResults = mutableListOf<BusinessResultMemory>()

    fun addTurn(turn: ConversationTurn) {
        turns.add(turn)
        if (turns.size > 20) {
            turns.removeAt(0)
        }
        lastToolUsed = turn.toolUsed
        lastToolResult = turn.toolResult
    }

    fun setBusinessSearchResults(results: List<BusinessResultMemory>, topic: String) {
        lastBusinessResults.clear()
        lastBusinessResults.addAll(results)
        activeTopic = topic
        if (results.isNotEmpty()) {
            val first = results[0]
            val ratingStr = first.rating?.let { "$it estrellas" } ?: "Sin datos de valoración"
            activeEntity = ConversationEntity(
                name = first.name,
                type = "business",
                value = "Dirección: ${first.address}, Tel: ${first.phone}, Rating: $ratingStr"
            )
        }
    }

    fun extractAndSetEntities(input: String, result: String) {
        val lowerInput = input.lowercase()
        val knownCountries = listOf("costa rica", "méxico", "mexico", "españa", "espana", "colombia", "argentina", "chile", "perú", "peru", "japón", "japon", "estados unidos", "eeuu")
        val knownCompanies = listOf("apple", "amazon", "google", "microsoft", "spacex", "openai", "meta", "tesla", "spotify", "youtube", "whatsapp", "instagram")

        for (country in knownCountries) {
            if (lowerInput.contains(country)) {
                val formattedName = country.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                activeEntity = ConversationEntity(name = formattedName, type = "country", value = result)
                activeTopic = formattedName
                return
            }
        }

        for (company in knownCompanies) {
            if (lowerInput.contains(company)) {
                val formattedName = company.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                activeEntity = ConversationEntity(name = formattedName, type = "company", value = result)
                activeTopic = formattedName
                return
            }
        }

        if (activeTopic == null && input.length in 3..40) {
            activeTopic = input
        }
    }

    fun resolveAnaphora(input: String): String {
        val norm = input.normalizeQuery().trim()

        if (lastBusinessResults.isNotEmpty()) {
            if (norm.contains("mas cerca") || norm.contains("mas cercana") || norm.contains("mas cercano") || norm.contains("el primero")) {
                val withDistance = lastBusinessResults.filter { it.distanceKm != null }
                if (withDistance.isNotEmpty()) {
                    val closest = withDistance.minByOrNull { it.distanceKm!! }
                    if (closest != null) {
                        activeEntity = ConversationEntity(name = closest.name, type = "business", value = "Dirección: ${closest.address}, Tel: ${closest.phone}")
                        return "$input (entre los negocios encontrados: ${closest.name} es el más cercano a ${closest.distanceKm} km)"
                    }
                } else {
                    return "$input (se encontraron ${lastBusinessResults.size} negocios, pero no se dispone de datos de distancia exactos)"
                }
            } else if (norm.contains("mejor valorac") || norm.contains("mejor califica") || norm.contains("mejor punte")) {
                val withRating = lastBusinessResults.filter { it.rating != null }
                if (withRating.isNotEmpty()) {
                    val topRated = withRating.maxByOrNull { it.rating!! }
                    if (topRated != null) {
                        activeEntity = ConversationEntity(name = topRated.name, type = "business", value = "Rating: ${topRated.rating}, Tel: ${topRated.phone}")
                        return "$input (entre los negocios encontrados: ${topRated.name} tiene la mejor valoración con ${topRated.rating} estrellas)"
                    }
                } else {
                    return "$input (se encontraron ${lastBusinessResults.size} negocios, pero no se dispone de valoraciones numéricas registradas)"
                }
            } else if (norm.contains("telefono") || norm.contains("direccion") || norm.contains("horario")) {
                val entityName = activeEntity?.name ?: lastBusinessResults.firstOrNull()?.name ?: ""
                if (entityName.isNotBlank()) {
                    return "$input (referente al negocio $entityName)"
                }
            } else if (norm.contains("abrelo") || norm.contains("abrir") || norm.contains("maps")) {
                val entityName = activeEntity?.name ?: lastBusinessResults.firstOrNull()?.name ?: ""
                if (entityName.isNotBlank()) {
                    return "abre google maps con la ubicación de $entityName"
                }
            }
        }

        val entity = activeEntity?.name ?: return input

        if (norm.contains("su capital") || norm.contains("su poblacion") ||
            norm.contains("de ese pais") || norm.contains("de la empresa") ||
            norm.contains("de esa empresa") || norm.contains("su presidente") || norm.contains("su historia") ||
            norm.contains("cual es su")) {
            return "$input (referente a $entity)"
        }

        return input
    }

    fun getSummarizedContext(): String {
        val entityStr = activeEntity?.let { "Entidad activa: ${it.name} (${it.type})" } ?: "Sin entidad activa"
        val topicStr = activeTopic?.let { "Tema actual: $it" } ?: "General"
        val businessStr = if (lastBusinessResults.isNotEmpty()) {
            "Últimos negocios encontrados (${lastBusinessResults.size}): " + lastBusinessResults.take(3).joinToString("; ") { "${it.name} (${it.address})" }
        } else {
            "Sin negocios en memoria"
        }
        val recentTurnsStr = turns.takeLast(3).joinToString("\n") { "Usuario: ${it.userInput} -> JARVIS: ${it.assistantOutput}" }
        return "$entityStr | $topicStr | $businessStr\nHistorial reciente:\n$recentTurnsStr"
    }

    fun clear() {
        turns.clear()
        activeEntity = null
        activeTopic = null
        lastToolUsed = null
        lastToolResult = null
        lastBusinessResults.clear()
    }
}
