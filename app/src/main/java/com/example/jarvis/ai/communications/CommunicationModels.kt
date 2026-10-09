package com.example.jarvis.ai.communications

import java.util.UUID

enum class ConversationType {
    DIRECT,
    GROUP,
    CHANNEL,
    UNKNOWN
}

enum class CommunicationMessageStatus {
    RECEIVED,
    READ,
    DRAFT,
    SENT,
    FAILED,
    UNKNOWN
}

enum class CommunicationDraftStatus {
    DRAFT,
    READY_FOR_REVIEW,
    APPROVED,
    SENT,
    CANCELLED
}

data class CommunicationParticipant(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val provider: String,
    val address: String = "",
    val metadata: Map<String, String> = emptyMap()
)

data class AttachmentMetadata(
    val id: String = UUID.randomUUID().toString(),
    val filename: String,
    val mimeType: String,
    val size: Long = 0L,
    val providerReference: String = ""
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val provider: String,
    val title: String,
    val participants: List<CommunicationParticipant> = emptyList(),
    val type: ConversationType = ConversationType.DIRECT,
    val lastMessageAt: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val metadata: Map<String, String> = emptyMap()
)

data class CommunicationMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val provider: String,
    val sender: String,
    val recipients: List<String> = emptyList(),
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentsMetadata: List<AttachmentMetadata> = emptyList(),
    val replyToMessageId: String? = null,
    val threadId: String? = null,
    val status: CommunicationMessageStatus = CommunicationMessageStatus.RECEIVED,
    val metadata: Map<String, String> = emptyMap()
)

data class CommunicationDraft(
    val id: String = UUID.randomUUID().toString(),
    val provider: String,
    val conversationId: String,
    val recipients: List<String> = emptyList(),
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: CommunicationDraftStatus = CommunicationDraftStatus.DRAFT,
    val sourceMessageId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class CommunicationCapabilities(
    val canReadMessages: Boolean = true,
    val canSearchMessages: Boolean = true,
    val canReadConversations: Boolean = true,
    val canCreateDraft: Boolean = true,
    val canSendMessages: Boolean = true,
    val canReply: Boolean = true,
    val canAttachments: Boolean = false,
    val canThreads: Boolean = false,
    val canGroups: Boolean = true,
    val canChannels: Boolean = false
)

data class ConfirmationToken(
    val provider: String,
    val conversationId: String,
    val recipients: List<String>,
    val body: String,
    val attachmentsMetadata: List<AttachmentMetadata> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isValidFor(provider: String, conversationId: String, recipients: List<String>, body: String, attachmentsMetadata: List<AttachmentMetadata>): Boolean {
        return this.provider.equals(provider, ignoreCase = true) &&
                this.conversationId == conversationId &&
                this.recipients.toSet() == recipients.toSet() &&
                this.body == body &&
                this.attachmentsMetadata == attachmentsMetadata
    }
}
