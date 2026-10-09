package com.example.jarvis.ai.tools

import com.example.jarvis.ai.communications.*
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class CommunicationSearchTool(
    private val engine: CommunicationsEngine = CommunicationsEngine()
) : Tool {
    override val name = "communication_search"
    override val description = "Busca mensajes en plataformas de comunicación"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: ""
        val results = engine.searchAll(query)
        return "Encontré ${results.size} mensajes."
    }
}

class CommunicationDraftTool(
    private val engine: CommunicationsEngine = CommunicationsEngine()
) : Tool {
    override val name = "communication_draft"
    override val description = "Prepara un borrador de mensaje de comunicación"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val provider = params["provider"]?.toString() ?: "whatsapp"
        val convId = params["conversationId"]?.toString() ?: "conv_1"
        val body = params["body"]?.toString() ?: ""
        val draft = CommunicationDraft(provider = provider, conversationId = convId, body = body)
        val created = engine.createDraft(provider, draft)
        return "Borrador creado con ID: ${created.id}"
    }
}

class CommunicationSendTool(
    private val engine: CommunicationsEngine = CommunicationsEngine()
) : Tool {
    override val name = "communication_send"
    override val description = "Envía un mensaje de comunicación (requiere confirmación explícita)"
    override val riskLevel = RiskLevel.DESTRUCTIVE

    override suspend fun execute(params: Map<String, Any>): String {
        val provider = params["provider"]?.toString() ?: "whatsapp"
        val convId = params["conversationId"]?.toString() ?: "conv_1"
        val body = params["body"]?.toString() ?: ""
        val confirmed = params["confirmed"]?.toString()?.toBoolean() ?: false
        val token = params["token"] as? ConfirmationToken

        val msg = CommunicationMessage(conversationId = convId, provider = provider, sender = "user", body = body)
        val success = engine.sendMessage(provider, msg, confirmed, token)
        return if (success) "Mensaje enviado correctamente." else "No se pudo enviar el mensaje."
    }
}
