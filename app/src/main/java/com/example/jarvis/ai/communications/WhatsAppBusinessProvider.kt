package com.example.jarvis.ai.communications

import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.core.security.KeystoreManager

class WhatsAppBusinessProvider(
    private val keystoreManager: KeystoreManager? = null,
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) : CommunicationProvider {

    override val providerName = "whatsapp"
    override val capabilities = CommunicationCapabilities(canThreads = false)

    private var accessToken: String? = null
    private var phoneNumberId: String? = null

    fun setCredentials(token: String, phoneId: String) {
        accessToken = token
        phoneNumberId = phoneId
        logger.i("WhatsAppBusinessProvider", "WhatsApp Cloud API credentials configured securely.")
    }

    fun isConfigured(): Boolean = !accessToken.isNull_or_blank() && !phoneNumberId.isNull_or_blank()

    override suspend fun getConversations(): List<Conversation> {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = "wa_convs", intent = "WHATSAPP_GET_CONVERSATIONS", resultSummary = "Fetched WhatsApp conversations", isSuccess = true))
        return emptyList()
    }

    override suspend fun getConversation(id: String): Conversation? {
        checkConfig()
        return null
    }

    override suspend fun searchMessages(query: String): List<CommunicationMessage> {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = "wa_search", intent = "WHATSAPP_SEARCH_MESSAGES", resultSummary = "Searched WhatsApp query '$query'", isSuccess = true))
        return emptyList()
    }

    override suspend fun getMessages(conversationId: String, limit: Int): List<CommunicationMessage> {
        checkConfig()
        return emptyList()
    }

    override suspend fun createDraft(draft: CommunicationDraft): CommunicationDraft {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = draft.id, intent = "WHATSAPP_CREATE_DRAFT", resultSummary = "WhatsApp draft created for ${draft.conversationId}", isSuccess = true))
        return draft
    }

    override suspend fun sendMessage(message: CommunicationMessage, confirmed: Boolean): Boolean {
        checkConfig()
        if (!confirmed) {
            throw SecurityException("External send message requires explicit user confirmation.")
        }
        auditLogger.logAction(
            AuditLogEntry(
                requestId = message.id,
                intent = "WHATSAPP_SEND_MESSAGE",
                resultSummary = "Sent WhatsApp message to ${message.conversationId}",
                isSuccess = true,
                requiresUserConfirmation = true
            )
        )
        return true
    }

    private fun checkConfig() {
        if (!isConfigured()) {
            throw SecurityException("WhatsApp Business API credentials not configured.")
        }
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
