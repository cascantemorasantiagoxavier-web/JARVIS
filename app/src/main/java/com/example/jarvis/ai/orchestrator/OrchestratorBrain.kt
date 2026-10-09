package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import com.example.jarvis.ai.orchestrator.models.OrchestratorResponse
import com.example.jarvis.domain.ai.AiProvider

/**
 * High-level Orchestrator Brain interface decoupling AI providers from execution pipelines.
 */
interface OrchestratorBrain {
    /**
     * Executes the complete 10-step JARVIS Brain pipeline.
     */
    suspend fun processRequest(request: OrchestratorRequest): OrchestratorResponse

    /**
     * Legacy alias for processRequest.
     */
    suspend fun process(request: OrchestratorRequest): OrchestratorResponse = processRequest(request)

    /**
     * Swaps or sets the underlying AI Provider dynamically without re-instantiating the brain.
     */
    fun setProvider(provider: AiProvider)
}
