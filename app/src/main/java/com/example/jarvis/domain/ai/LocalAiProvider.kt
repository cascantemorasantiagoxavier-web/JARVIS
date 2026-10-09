package com.example.jarvis.domain.ai

import android.content.Context
import com.example.jarvis.data.local.ai.GemmaLocalAiProvider
import com.example.jarvis.data.remote.ai.ChatMessagePayload

class LocalAiProvider(
    context: Context? = null,
    private val gemmaProvider: GemmaLocalAiProvider = GemmaLocalAiProvider(context)
) : AiProvider {

    fun setContext(context: Context) {
        gemmaProvider.setApplicationContext(context)
    }

    fun isLocalAvailable(): Boolean {
        return gemmaProvider.isModelAvailable()
    }

    override suspend fun createSession(): Result<AiSession> {
        return gemmaProvider.createSession()
    }

    override suspend fun sendText(session: AiSession, text: String): Result<Unit> {
        return gemmaProvider.sendText(session, text)
    }

    override suspend fun disconnect(session: AiSession) {
        gemmaProvider.disconnect(session)
    }

    suspend fun generateResponse(
        messages: List<ChatMessagePayload>,
        systemPrompt: String? = null
    ): Result<String> {
        return gemmaProvider.generateResponse(messages, systemPrompt = systemPrompt)
    }
}
