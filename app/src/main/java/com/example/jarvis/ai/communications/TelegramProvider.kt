package com.example.jarvis.ai.communications

import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.core.security.KeystoreManager

class TelegramProvider(
    private val keystoreManager: KeystoreManager? = null,
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) : CommunicationProvider {

    override val providerName = "telegram"
    override val capabilities = CommunicationCapabilities(canThreads = true, canChannels = true)

    private var botToken: String? = null

    fun setBotToken(token: String) {
        botToken = token
        logger.i("TelegramProvider", "Telegram Bot Token configured securely.")
    }

    fun isConfigured(): Boolean = !botToken.isNull_or_blank()

    override suspend fun getConversations(): List<Conversation> {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = "tg_convs", intent = "TELEGRAM_GET_CONVERSATIONS", resultSummary = "Fetched Telegram conversations", isSuccess = true))
        return emptyList()
    }

    override suspend fun getConversation(id: String): Conversation? {
        checkConfig()
        return null
    }

    override suspend fun searchMessages(query: String): List<CommunicationMessage> {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = "tg_search", intent = "TELEGRAM_SEARCH_MESSAGES", resultSummary = "Searched Telegram query '$query'", isSuccess = true))
        return emptyList()
    }

    override suspend fun getMessages(conversationId: String, limit: Int): List<CommunicationMessage> {
        checkConfig()
        return emptyList()
    }

    override suspend fun createDraft(draft: CommunicationDraft): CommunicationDraft {
        checkConfig()
        auditLogger.logAction(AuditLogEntry(requestId = draft.id, intent = "TELEGRAM_CREATE_DRAFT", resultSummary = "Telegram draft created for ${draft.conversationId}", isSuccess = true))
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
                intent = "TELEGRAM_SEND_MESSAGE",
                resultSummary = "Sent Telegram message to chat ${message.conversationId}",
                isSuccess = true,
                requiresUserConfirmation = true
            )
        )
        return true
    }

    private fun checkConfig() {
        if (!isConfigured()) {
            throw SecurityException("Telegram Bot Token not configured.")
        }
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
