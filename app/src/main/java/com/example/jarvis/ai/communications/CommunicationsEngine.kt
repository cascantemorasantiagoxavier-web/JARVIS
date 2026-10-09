package com.example.jarvis.ai.communications

class CommunicationsEngine(
    private val providers: List<CommunicationProvider> = listOf(
        MockWhatsAppProvider(),
        MockTelegramProvider(),
        MockSlackProvider()
    )
) {
    suspend fun searchAll(query: String): List<CommunicationMessage> {
        return providers.flatMap { it.searchMessages(query) }
    }

    suspend fun getConversations(): List<Conversation> {
        return providers.flatMap { it.getConversations() }
    }

    suspend fun createDraft(providerName: String, draft: CommunicationDraft): CommunicationDraft {
        val provider = providers.find { it.providerName.equals(providerName, ignoreCase = true) }
            ?: throw IllegalArgumentException("Provider $providerName not supported")
        if (!provider.capabilities.canCreateDraft) {
            throw UnsupportedOperationException("Provider $providerName does not support creating drafts")
        }
        return provider.createDraft(draft)
    }

    suspend fun generateConfirmationToken(message: CommunicationMessage): ConfirmationToken {
        return ConfirmationToken(
            provider = message.provider,
            conversationId = message.conversationId,
            recipients = message.recipients,
            body = message.body,
            attachmentsMetadata = message.attachmentsMetadata
        )
    }

    suspend fun sendMessage(
        providerName: String,
        message: CommunicationMessage,
        confirmed: Boolean,
        token: ConfirmationToken?
    ): Boolean {
        if (!confirmed) {
            throw SecurityException("External send message requires explicit user confirmation.")
        }
        val provider = providers.find { it.providerName.equals(providerName, ignoreCase = true) }
            ?: throw IllegalArgumentException("Provider $providerName not supported")

        if (!provider.capabilities.canSendMessages) {
            throw UnsupportedOperationException("Provider $providerName does not support sending messages")
        }

        if (token == null || !token.isValidFor(message.provider, message.conversationId, message.recipients, message.body, message.attachmentsMetadata)) {
            throw SecurityException("Confirmation token is invalid or content (recipient, provider, conversation, body, or attachments) was modified after confirmation.")
        }

        return provider.sendMessage(message, confirmed)
    }

    fun getProvider(providerName: String): CommunicationProvider? {
        return providers.find { it.providerName.equals(providerName, ignoreCase = true) }
    }
}
