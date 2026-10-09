package com.example.jarvis.ai.routing

import com.example.jarvis.ai.orchestrator.models.IntentType

enum class ComplexityLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class RoutingContext(
    val intentType: IntentType,
    val requiresTools: Boolean = false,
    val complexity: ComplexityLevel = ComplexityLevel.LOW,
    val inputLength: Int = 0,
    val requiresReasoning: Boolean = false,
    val isVoice: Boolean = false
)

data class ModelSelection(
    val selectedModel: AiModelConfig,
    val reasoning: String,
    val complexity: ComplexityLevel,
    val requiresReasoning: Boolean,
    val requiresTools: Boolean,
    val fallbackModel: AiModelConfig? = null
)
