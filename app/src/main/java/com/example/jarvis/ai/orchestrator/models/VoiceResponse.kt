package com.example.jarvis.ai.orchestrator.models

/**
 * Categorized response types for speech synthesis via GPT Realtime.
 */
enum class VoiceResponseType {
    CONVERSATION,
    ACTION,
    QUESTION,
    TOOL_RESULT,
    ERROR
}

/**
 * Abstraction representing the output response to be pronounced by the Voice subsystem.
 */
data class VoiceResponse(
    val text: String,
    val responseType: VoiceResponseType,
    val toolResult: String? = null,
    val error: OrchestratorError? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        fun fromOrchestratorResponse(response: OrchestratorResponse): VoiceResponse {
            val intentType = response.detectedIntent?.type
            val type = when {
                response.error != null -> VoiceResponseType.ERROR
                response.toolSelection?.toolName != null -> VoiceResponseType.TOOL_RESULT
                intentType == IntentType.QUESTION -> VoiceResponseType.QUESTION
                intentType == IntentType.OPEN_APP || intentType == IntentType.OPEN_SETTINGS || intentType == IntentType.DEVICE -> VoiceResponseType.ACTION
                else -> VoiceResponseType.CONVERSATION
            }
            return VoiceResponse(
                text = response.output,
                responseType = type,
                toolResult = if (response.toolSelection?.toolName != null) response.output else null,
                error = response.error
            )
        }
    }
}
