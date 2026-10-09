package com.example.jarvis.ai.communications

interface CommunicationProvider {
    val providerName: String
    val capabilities: CommunicationCapabilities

    suspend fun getConversations(): List<Conversation>
    suspend fun getConversation(id: String): Conversation?
    suspend fun searchMessages(query: String): List<CommunicationMessage>
    suspend fun getMessages(conversationId: String, limit: Int = 20): List<CommunicationMessage>
    suspend fun createDraft(draft: CommunicationDraft): CommunicationDraft
    suspend fun sendMessage(message: CommunicationMessage, confirmed: Boolean): Boolean
}

class MockWhatsAppProvider : CommunicationProvider {
    override val providerName = "whatsapp"
    override val capabilities = CommunicationCapabilities(canThreads = false)

    private val conversations = mutableMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<CommunicationMessage>>()
    private val drafts = mutableMapOf<String, CommunicationDraft>()

    init {
        val conv = Conversation(id = "wa_conv_1", provider = providerName, title = "Carlos WhatsApp", type = ConversationType.DIRECT)
        conversations[conv.id] = conv
        messages[conv.id] = mutableListOf(
            CommunicationMessage(id = "wa_msg_1", conversationId = conv.id, provider = providerName, sender = "Carlos", body = "Hola, ¿cómo va el proyecto JARVIS?")
        )
    }

    override suspend fun getConversations(): List<Conversation> = conversations.values.toList()
    override suspend fun getConversation(id: String): Conversation? = conversations[id]
    override suspend fun searchMessages(query: String): List<CommunicationMessage> {
        return messages.values.flatten().filter { it.body.contains(query, ignoreCase = true) || it.sender.contains(query, ignoreCase = true) }
    }
    override suspend fun getMessages(conversationId: String, limit: Int): List<CommunicationMessage> {
        return messages[conversationId]?.takeLast(limit) ?: emptyList()
    }
    override suspend fun createDraft(draft: CommunicationDraft): CommunicationDraft {
        drafts[draft.id] = draft
        return draft
    }
    override suspend fun sendMessage(message: CommunicationMessage, confirmed: Boolean): Boolean {
        if (!confirmed) throw SecurityException("External send message requires explicit confirmation.")
        messages.getOrPut(message.conversationId) { mutableListOf() }.add(message.copy(status = CommunicationMessageStatus.SENT))
        return true
    }
}

class MockTelegramProvider : CommunicationProvider {
    override val providerName = "telegram"
    override val capabilities = CommunicationCapabilities(canThreads = true, canChannels = true)

    private val conversations = mutableMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<CommunicationMessage>>()
    private val drafts = mutableMapOf<String, CommunicationDraft>()

    init {
        val conv = Conversation(id = "tg_conv_1", provider = providerName, title = "JARVIS Dev Group", type = ConversationType.GROUP)
        conversations[conv.id] = conv
        messages[conv.id] = mutableListOf(
            CommunicationMessage(id = "tg_msg_1", conversationId = conv.id, provider = providerName, sender = "Juan", body = "Revisemos el PR de Memory 2.0 hoy.")
        )
    }

    override suspend fun getConversations(): List<Conversation> = conversations.values.toList()
    override suspend fun getConversation(id: String): Conversation? = conversations[id]
    override suspend fun searchMessages(query: String): List<CommunicationMessage> {
        return messages.values.flatten().filter { it.body.contains(query, ignoreCase = true) || it.sender.contains(query, ignoreCase = true) }
    }
    override suspend fun getMessages(conversationId: String, limit: Int): List<CommunicationMessage> {
        return messages[conversationId]?.takeLast(limit) ?: emptyList()
    }
    override suspend fun createDraft(draft: CommunicationDraft): CommunicationDraft {
        drafts[draft.id] = draft
        return draft
    }
    override suspend fun sendMessage(message: CommunicationMessage, confirmed: Boolean): Boolean {
        if (!confirmed) throw SecurityException("External send message requires explicit confirmation.")
        messages.getOrPut(message.conversationId) { mutableListOf() }.add(message.copy(status = CommunicationMessageStatus.SENT))
        return true
    }
}

class MockSlackProvider : CommunicationProvider {
    override val providerName = "slack"
    override val capabilities = CommunicationCapabilities(canThreads = true, canGroups = true)

    private val conversations = mutableMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<CommunicationMessage>>()
    private val drafts = mutableMapOf<String, CommunicationDraft>()

    init {
        val conv = Conversation(id = "sl_conv_1", provider = providerName, title = "#general", type = ConversationType.CHANNEL)
        conversations[conv.id] = conv
        messages[conv.id] = mutableListOf(
            CommunicationMessage(id = "sl_msg_1", conversationId = conv.id, provider = providerName, sender = "Ana", body = "Deploy exitoso a producción.")
        )
    }

    override suspend fun getConversations(): List<Conversation> = conversations.values.toList()
    override suspend fun getConversation(id: String): Conversation? = conversations[id]
    override suspend fun searchMessages(query: String): List<CommunicationMessage> {
        return messages.values.flatten().filter { it.body.contains(query, ignoreCase = true) || it.sender.contains(query, ignoreCase = true) }
    }
    override suspend fun getMessages(conversationId: String, limit: Int): List<CommunicationMessage> {
        return messages[conversationId]?.takeLast(limit) ?: emptyList()
    }
    override suspend fun createDraft(draft: CommunicationDraft): CommunicationDraft {
        drafts[draft.id] = draft
        return draft
    }
    override suspend fun sendMessage(message: CommunicationMessage, confirmed: Boolean): Boolean {
        if (!confirmed) throw SecurityException("External send message requires explicit confirmation.")
        messages.getOrPut(message.conversationId) { mutableListOf() }.add(message.copy(status = CommunicationMessageStatus.SENT))
        return true
    }
}
