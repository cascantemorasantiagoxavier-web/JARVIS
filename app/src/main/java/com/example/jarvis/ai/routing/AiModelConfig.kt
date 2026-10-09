package com.example.jarvis.ai.routing

enum class ModelPurpose {
    VOICE,
    FAST,
    REASONING
}

data class AiModelConfig(
    val id: String,
    val purpose: ModelPurpose,
    val maxTokens: Int,
    val capabilities: List<String>,
    val costTier: String,
    val latencyTier: String
)

object ModelRegistry {
    val VOICE_MODEL = AiModelConfig(
        id = "gpt-realtime-2.1",
        purpose = ModelPurpose.VOICE,
        maxTokens = 4096,
        capabilities = listOf("realtime_audio", "voice", "websocket"),
        costTier = "high",
        latencyTier = "realtime"
    )

    val FAST_MODEL = AiModelConfig(
        id = "gpt-5.6-luna",
        purpose = ModelPurpose.FAST,
        maxTokens = 2048,
        capabilities = listOf("classification", "extraction", "summary", "fast_completion"),
        costTier = "low",
        latencyTier = "fast"
    )

    val REASONING_MODEL = AiModelConfig(
        id = "gpt-5.6-sol",
        purpose = ModelPurpose.REASONING,
        maxTokens = 8192,
        capabilities = listOf("complex_planning", "deep_analysis", "coding", "multi_step"),
        costTier = "medium_high",
        latencyTier = "standard"
    )
}
