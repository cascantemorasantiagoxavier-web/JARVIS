package com.example.jarvis.ai.orchestrator.models

/**
 * Abstraction representing a recognized speech command coming from the Voice subsystem.
 */
data class VoiceCommand(
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = "default_voice_session",
    val origin: String = "VOICE",
    val metadata: Map<String, Any> = emptyMap()
) {
    fun toOrchestratorRequest(): OrchestratorRequest {
        return OrchestratorRequest(
            input = text,
            sessionId = sessionId,
            metadata = metadata + ("origin" to origin)
        )
    }
}
