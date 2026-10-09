package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.AgentRegistry
import com.example.jarvis.ai.agents.IntentDetector
import com.example.jarvis.ai.agents.ToolRegistry
import com.example.jarvis.ai.context.ContextManager
import com.example.jarvis.ai.memory.AiMemoryManager
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import com.example.jarvis.ai.orchestrator.models.OrchestratorResponse
import com.example.jarvis.domain.ai.AiProvider

/**
 * 10-step JARVIS Orchestration Flow implementation.
 */
class DefaultAiOrchestrator(
    private val intentDetector: IntentDetector,
    private val contextManager: ContextManager,
    private val memoryManager: AiMemoryManager,
    private val agentRegistry: AgentRegistry,
    private val toolRegistry: ToolRegistry,
    agentSelector: AgentSelector = DefaultAgentSelector(agentRegistry),
    toolSelector: ToolSelector = DefaultToolSelector(toolRegistry),
    contextResolver: ContextRequirementResolver = DefaultContextRequirementResolver(),
    validator: ResultValidator = DefaultResultValidator(),
    memoryDecider: MemoryDecider = DefaultMemoryDecider(),
    provider: AiProvider? = null
) : AiOrchestrator, OrchestratorBrain {

    private val brain: OrchestratorBrain = DefaultOrchestratorBrain(
        intentDetector = intentDetector,
        contextManager = contextManager,
        memoryManager = memoryManager,
        agentSelector = agentSelector,
        toolSelector = toolSelector,
        contextResolver = contextResolver,
        validator = validator,
        memoryDecider = memoryDecider,
        provider = provider
    )

    override fun setProvider(provider: AiProvider) {
        brain.setProvider(provider)
    }

    override suspend fun processRequest(request: OrchestratorRequest): OrchestratorResponse {
        return brain.processRequest(request)
    }

    override suspend fun process(request: OrchestratorRequest): OrchestratorResponse {
        return brain.processRequest(request)
    }

    override suspend fun coordinate(input: String): String {
        val request = OrchestratorRequest(input = input)
        val response = process(request)
        return response.output
    }
}
