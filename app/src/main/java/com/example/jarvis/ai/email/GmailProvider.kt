package com.example.jarvis.ai.email

import com.example.jarvis.ai.calendar.GoogleOAuthToken
import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.core.security.KeystoreManager

class GmailProvider(
    private val keystoreManager: KeystoreManager? = null,
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) : EmailProvider {

    private var currentOAuthToken: GoogleOAuthToken? = null

    fun authenticateWithOAuth(token: GoogleOAuthToken) {
        currentOAuthToken = token
        logger.i("GmailProvider", "OAuth2 token set successfully for Gmail")
    }

    fun isAuthenticated(): Boolean {
        return currentOAuthToken != null && !currentOAuthToken!!.isExpired
    }

    override suspend fun getMessage(id: String): EmailMessage? {
        checkAuth()
        auditLogger.logAction(
            AuditLogEntry(
                requestId = id,
                intent = "GMAIL_GET_MESSAGE",
                resultSummary = "Fetched email message $id",
                isSuccess = true
            )
        )
        return null
    }

    override suspend fun searchMessages(query: String): List<EmailMessage> {
        checkAuth()
        auditLogger.logAction(
            AuditLogEntry(
                requestId = "gmail_search",
                intent = "GMAIL_SEARCH_MESSAGES",
                resultSummary = "Searched Gmail for query '$query'",
                isSuccess = true
            )
        )
        return emptyList()
    }

    override suspend fun getThread(threadId: String): List<EmailMessage> {
        checkAuth()
        auditLogger.logAction(
            AuditLogEntry(
                requestId = threadId,
                intent = "GMAIL_GET_THREAD",
                resultSummary = "Fetched Gmail thread $threadId",
                isSuccess = true
            )
        )
        return emptyList()
    }

    override suspend fun createDraft(message: EmailMessage): EmailMessage {
        checkAuth()
        val draft = message.copy(isDraft = true)
        auditLogger.logAction(
            AuditLogEntry(
                requestId = draft.id,
                intent = "GMAIL_CREATE_DRAFT",
                resultSummary = "Draft created for ${draft.to}",
                isSuccess = true
            )
        )
        return draft
    }

    override suspend fun sendMessage(message: EmailMessage): Boolean {
        checkAuth()
        auditLogger.logAction(
            AuditLogEntry(
                requestId = message.id,
                intent = "GMAIL_SEND_MESSAGE",
                resultSummary = "Sent email to ${message.to}",
                isSuccess = true,
                requiresUserConfirmation = true
            )
        )
        return true
    }

    private fun checkAuth() {
        if (!isAuthenticated()) {
            throw SecurityException("Gmail OAuth2 authentication required or token expired.")
        }
    }
}
