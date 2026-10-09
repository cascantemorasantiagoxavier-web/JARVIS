package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import com.example.jarvis.ai.orchestrator.models.OrchestratorResponse

interface AiOrchestrator {
    suspend fun coordinate(input: String): String
    suspend fun process(request: OrchestratorRequest): OrchestratorResponse
}
