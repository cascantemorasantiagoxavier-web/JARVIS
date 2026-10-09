package com.example.jarvis.ai.email

import java.util.UUID

enum class EmailOperation {
    READ,
    SEARCH,
    DRAFT,
    SEND
}

data class EmailMessage(
    val id: String = UUID.randomUUID().toString(),
    val threadId: String = id,
    val from: String,
    val to: String,
    val subject: String,
    val body: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val isDraft: Boolean = false
)
