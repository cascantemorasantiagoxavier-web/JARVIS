package com.example.jarvis.ai.tools

import com.example.jarvis.ai.research.*
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class ResearchTool(
    private val researchEngine: ResearchEngine = ResearchEngine()
) : Tool {
    override val name = "research_topic"
    override val description = "Investiga un tema recopilando fuentes, evidencia y síntesis real en línea"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString()
            ?: params["topic"]?.toString()
            ?: return "Consulta de investigación vacía."

        val result = researchEngine.investigate(ResearchRequest(query = query))
        if (result.sources.isEmpty()) {
            return "No pude obtener resultados web para '$query'. Verifica la conexión a Internet."
        }

        val sourcesFormatted = result.sources.take(3).joinToString("\n") { src ->
            "- ${src.title} (${src.url})"
        }

        return "Investigación sobre '$query':\n${result.summary}\n\nFuentes reales encontradas:\n$sourcesFormatted"
    }
}
