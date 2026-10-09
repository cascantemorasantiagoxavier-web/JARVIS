package com.example.jarvis.ai.agents

import com.example.jarvis.domain.agents.Agent

class DefaultAgentRegistry : AgentRegistry {
    private val agents = mutableMapOf<String, Agent>()

    init {
        registerAgent(DeviceAgentImpl())
        registerAgent(CodingAgentImpl())
        registerAgent(MemoryAgentImpl())
        registerAgent(ObsidianAgentImpl())
        registerAgent(OrchestratorAgentImpl())
        registerAgent(PlanningAgentImpl())
        registerAgent(ProductivityAgentImpl())
        registerAgent(ResearchAgentImpl())
        registerAgent(CommunicationAgentImpl())
    }

    override fun registerAgent(agent: Agent) {
        agents[agent.id] = agent
    }

    override fun getAgent(id: String): Agent? {
        return agents[id]
    }
}
