
package com.example.jarvis.core.di

import com.example.jarvis.domain.ai.ThinkingMode

enum class RequestSource {
    CHAT,
    VOICE
}

data class JarvisRequest(
    val text: String,
    val source: RequestSource = RequestSource.CHAT,
    val conversationId: String = "default_conv",
    val timestamp: Long = System.currentTimeMillis(),
    val requestedMode: ThinkingMode = ThinkingMode.AUTO
)

data class JarvisResponse(
    val text: String,
    val action: String? = null,
    val toolUsed: String? = null,
    val success: Boolean = true,
    val thinkingMode: ThinkingMode = ThinkingMode.AUTO,
    val memoryUpdated: Boolean = false,
    val executionTimeMs: Long = 0L
)
