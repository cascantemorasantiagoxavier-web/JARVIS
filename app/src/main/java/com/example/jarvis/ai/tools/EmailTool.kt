package com.example.jarvis.ai.tools

import com.example.jarvis.ai.email.*
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class EmailTool(
    private val emailEngine: EmailEngine = EmailEngine()
) : Tool {
    override val name = "email_search"
    override val description = "Busca correos electrónicos relevantes"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: ""
        val messages = emailEngine.search(query)
        return if (messages.isNotEmpty()) {
            "Encontré ${messages.size} correos: ${messages.joinToString { it.subject }}"
        } else {
            "No se encontraron correos."
        }
    }
}
