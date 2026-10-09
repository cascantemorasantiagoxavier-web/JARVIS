package com.example.jarvis.ai.email

class EmailEngine(
    private val provider: EmailProvider = MockEmailProvider()
) {
    suspend fun search(query: String): List<EmailMessage> {
        return provider.searchMessages(query)
    }

    suspend fun getThread(threadId: String): List<EmailMessage> {
        return provider.getThread(threadId)
    }

    suspend fun createDraft(message: EmailMessage): EmailMessage {
        return provider.createDraft(message)
    }

    suspend fun sendWithConfirmation(message: EmailMessage, confirmed: Boolean): Boolean {
        if (!confirmed) {
            throw SecurityException("External write action 'send email' requires explicit user confirmation.")
        }
        return provider.sendMessage(message)
    }
}
