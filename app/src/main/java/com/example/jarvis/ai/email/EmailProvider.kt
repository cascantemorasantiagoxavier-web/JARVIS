package com.example.jarvis.ai.email

interface EmailProvider {
    suspend fun getMessage(id: String): EmailMessage?
    suspend fun searchMessages(query: String): List<EmailMessage>
    suspend fun getThread(threadId: String): List<EmailMessage>
    suspend fun createDraft(message: EmailMessage): EmailMessage
    suspend fun sendMessage(message: EmailMessage): Boolean
}

class MockEmailProvider : EmailProvider {
    private val messagesStore = mutableMapOf<String, EmailMessage>()

    init {
        val sample = EmailMessage(
            id = "email_1",
            from = "carlos@example.com",
            to = "user@jarvis.com",
            subject = "Project Update",
            body = "Please review the documents before Friday."
        )
        messagesStore[sample.id] = sample
    }

    override suspend fun getMessage(id: String): EmailMessage? {
        return messagesStore[id]
    }

    override suspend fun searchMessages(query: String): List<EmailMessage> {
        return messagesStore.values.filter {
            it.subject.contains(query, ignoreCase = true) ||
                    it.body.contains(query, ignoreCase = true) ||
                    it.from.contains(query, ignoreCase = true)
        }
    }

    override suspend fun getThread(threadId: String): List<EmailMessage> {
        return messagesStore.values.filter { it.threadId == threadId }.sortedBy { it.receivedAt }
    }

    override suspend fun createDraft(message: EmailMessage): EmailMessage {
        val draft = message.copy(isDraft = true)
        messagesStore[draft.id] = draft
        return draft
    }

    override suspend fun sendMessage(message: EmailMessage): Boolean {
        messagesStore[message.id] = message.copy(isDraft = false)
        return true
    }
}
