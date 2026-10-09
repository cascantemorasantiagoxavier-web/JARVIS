package com.example.jarvis.ai.routing

import com.example.jarvis.ai.orchestrator.models.IntentType
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest

interface ModelRouter {
    fun selectModel(request: OrchestratorRequest, context: RoutingContext): ModelSelection
}

class DefaultModelRouter : ModelRouter {
    override fun selectModel(request: OrchestratorRequest, context: RoutingContext): ModelSelection {
        // Rule 1: Voice conversations always use VOICE_MODEL
        if (context.isVoice || request.metadata["origin"] == "VOICE" || context.intentType == IntentType.CONVERSATION) {
            return ModelSelection(
                selectedModel = ModelRegistry.VOICE_MODEL,
                reasoning = "Voice interaction mapped to real-time voice model",
                complexity = ComplexityLevel.LOW,
                requiresReasoning = false,
                requiresTools = false,
                fallbackModel = ModelRegistry.FAST_MODEL
            )
        }

        // Rule 2: Device actions or simple deterministic tasks require no expensive model
        if (context.intentType == IntentType.DEVICE || context.intentType == IntentType.OPEN_APP || context.intentType == IntentType.OPEN_SETTINGS || context.intentType == IntentType.DEVICE_INFO) {
            return ModelSelection(
                selectedModel = ModelRegistry.FAST_MODEL,
                reasoning = "Deterministic device action/tool routing",
                complexity = ComplexityLevel.LOW,
                requiresReasoning = false,
                requiresTools = true,
                fallbackModel = ModelRegistry.REASONING_MODEL
            )
        }

        // Rule 3: Complex planning, research, coding, multi-step -> REASONING_MODEL
        if (context.intentType == IntentType.PLANNING || context.intentType == IntentType.RESEARCH || context.intentType == IntentType.CODING || context.complexity == ComplexityLevel.HIGH || context.requiresReasoning) {
            return ModelSelection(
                selectedModel = ModelRegistry.REASONING_MODEL,
                reasoning = "Complex reasoning, planning or coding task requires advanced model",
                complexity = ComplexityLevel.HIGH,
                requiresReasoning = true,
                requiresTools = context.requiresTools,
                fallbackModel = ModelRegistry.FAST_MODEL
            )
        }

        // Default: FAST_MODEL for classification, memory search, tasks, Obsidian search
        return ModelSelection(
            selectedModel = ModelRegistry.FAST_MODEL,
            reasoning = "Standard task routed to fast/economical model",
            complexity = ComplexityLevel.MEDIUM,
            requiresReasoning = false,
            requiresTools = context.requiresTools,
            fallbackModel = ModelRegistry.REASONING_MODEL
        )
    }
}
