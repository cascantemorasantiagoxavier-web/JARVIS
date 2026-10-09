package com.example.jarvis.domain.agents

data class AgentResponse(
    val requestId: String,
    val output: String,
    val success: Boolean,
    val metadata: Map<String, Any> = emptyMap()
)
