package com.example.jarvis.domain.agents

data class AgentRequest(
    val requestId: String,
    val input: String,
    val context: Map<String, String> = emptyMap()
)
