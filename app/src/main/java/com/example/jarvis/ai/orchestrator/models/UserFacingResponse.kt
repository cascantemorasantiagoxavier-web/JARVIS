package com.example.jarvis.ai.orchestrator.models

import com.example.jarvis.ai.research.ResearchSource

data class UserFacingResponse(
    val text: String,
    val sources: List<ResearchSource> = emptyList(),
    val isSuccess: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)
