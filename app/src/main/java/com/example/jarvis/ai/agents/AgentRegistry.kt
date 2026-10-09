package com.example.jarvis.ai.agents

import com.example.jarvis.domain.agents.Agent

interface AgentRegistry {
    fun registerAgent(agent: Agent)
    fun getAgent(id: String): Agent?
}
