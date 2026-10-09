package com.example.jarvis.domain.agents

interface OrchestratorAgent : Agent {
    suspend fun executeTask(request: AgentRequest): AgentResponse
}
