package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.AgentSelection
import com.example.jarvis.ai.orchestrator.models.DetectedIntent
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest

/**
 * Abstraction responsible for selecting the appropriate Agent based on intent and request.
 */
interface AgentSelector {
    suspend fun selectAgent(intent: DetectedIntent, request: OrchestratorRequest): AgentSelection
}
