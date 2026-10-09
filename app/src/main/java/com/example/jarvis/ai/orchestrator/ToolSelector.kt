package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.DetectedIntent
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import com.example.jarvis.ai.orchestrator.models.ToolSelection

/**
 * Abstraction responsible for selecting the appropriate Tool based on intent and request.
 */
interface ToolSelector {
    suspend fun selectTool(intent: DetectedIntent, request: OrchestratorRequest): ToolSelection
}
