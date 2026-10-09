package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.OrchestratorResponse
import com.example.jarvis.ai.orchestrator.models.UserFacingResponse

object UserResponseMapper {

    fun map(response: OrchestratorResponse): UserFacingResponse {
        val rawOutput = response.output
        val toolName = response.toolSelection?.toolName
        val intentType = response.detectedIntent?.type?.name ?: "GENERAL"

        val isLeakedInternal = rawOutput.contains("Orchestrated response for", ignoreCase = true) ||
                rawOutput.contains("Processed intent:", ignoreCase = true) ||
                rawOutput.contains("ToolResult", ignoreCase = true) ||
                rawOutput.contains("OrchestratorResult", ignoreCase = true)

        val isError = !response.isSuccess || response.error != null || rawOutput.startsWith("Error", ignoreCase = true) || rawOutput.contains("Exception", ignoreCase = true) || rawOutput.contains("No fue posible", ignoreCase = true) || rawOutput.contains("GEMMA_NOT_AVAILABLE", ignoreCase = true)

        val cleanText = if (isError || isLeakedInternal) {
            when {
                rawOutput.isNotBlank() && !isLeakedInternal -> rawOutput
                intentType == "WEB_SEARCH" || intentType == "RESEARCH" -> "No he podido completar la búsqueda web en este momento. Verifica tu conexión a Internet o la configuración del servidor."
                intentType == "DATE_TIME_QUERY" -> "No he podido consultar la hora actual del sistema."
                intentType == "CALCULATOR_QUERY" -> "No he podido calcular esa operación matemática."
                intentType == "BATTERY_QUERY" -> "No he podido consultar el estado de la batería."
                intentType == "NETWORK_QUERY" -> "No he podido verificar el estado de la red."
                else -> "No he podido procesar tu consulta con los servicios online u offline de JARVIS. Detalle: $rawOutput"
            }
        } else {
            when {
                toolName == "get_date_time" -> rawOutput
                toolName == "calculator" -> rawOutput
                toolName == "battery_info" -> rawOutput
                toolName == "check_network" -> rawOutput
                toolName == "research_topic" -> {
                    if (rawOutput.contains("Investigación sobre")) {
                        rawOutput.substringBefore("Fuentes reales encontradas").trim()
                    } else {
                        rawOutput
                    }
                }
                else -> rawOutput
            }
        }

        return UserFacingResponse(
            text = cleanText,
            isSuccess = response.isSuccess && !isLeakedInternal && !isError,
            metadata = mapOf("intent" to intentType, "tool" to (toolName ?: "none"))
        )
    }
}
