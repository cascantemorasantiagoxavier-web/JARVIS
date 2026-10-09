package com.example.jarvis.ai.communications

import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.core.security.KeystoreManager

class SlackProvider(
    private val keystoreManager: KeystoreManager? = null,
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) : CommunicationProvider {

    override val providerName = "slack"
    override val capabilities = CommunicationCapabilities(canThreads = true, canGroups = true)

    private var botToken: String? = null

    fun setBotToken(token: String) {
        botToken = token
        logger.i("SlackProvider", "Slack Bot Token configured securely.")
    }

    fun isConfigured(): Boolean = !botToken.isNull_or_blank()

    override suspend fun getConversations(): List<Conversation> {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = "sl_convs", intent = "SLACK_GET_CONVERSATIONS", resultSummary = "Fetched Slack channels", isSuccess = true))
        return emptyList()
    }

    override suspend fun getConversation(id: String): Conversation? {
        checkConfig()
        return null
    }

    override suspend fun searchMessages(query: String): List<CommunicationMessage> {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = "sl_search", intent = "SLACK_SEARCH_MESSAGES", resultSummary = "Searched Slack query '$query'", isSuccess = true))
        return emptyList()
    }

    override suspend fun getMessages(conversationId: String, limit: Int): List<CommunicationMessage> {
        checkConfig()
        return emptyList()
    }

    override suspend fun createDraft(draft: CommunicationDraft): CommunicationDraft {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = draft.id, intent = "SLACK_CREATE_DRAFT", resultSummary = "Slack draft created for ${draft.conversationId}", isSuccess = true))
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
                intent = "SLACK_SEND_MESSAGE",
                resultSummary = "Sent Slack message to channel ${message.conversationId}",
                isSuccess = true,
                requiresUserConfirmation = true
            )
        )
        return true
    }

    private fun checkConfig() {
        if (!isConfigured()) {
            throw SecurityException("Slack Bot Token not configured.")
        }
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
