package com.example.jarvis.domain.ai

interface AiProvider {
    suspend fun createSession(): Result<AiSession>
    suspend fun sendText(session: AiSession, text: String): Result<Unit>
    suspend fun disconnect(session: AiSession)
}
