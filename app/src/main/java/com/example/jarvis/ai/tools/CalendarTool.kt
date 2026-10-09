package com.example.jarvis.ai.tools

import com.example.jarvis.ai.calendar.*
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class CalendarTool(
    private val calendarEngine: CalendarEngine = CalendarEngine()
) : Tool {
    override val name = "calendar_query"
    override val description = "Consulta eventos del calendario"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: ""
        val events = calendarEngine.search(query)
        return if (events.isNotEmpty()) {
            "Encontré ${events.size} eventos: ${events.joinToString { it.title }}"
        } else {
            "No se encontraron eventos en el calendario."
        }
    }
}
