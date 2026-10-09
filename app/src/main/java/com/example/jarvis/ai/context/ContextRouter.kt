package com.example.jarvis.ai.context

import com.example.jarvis.ai.orchestrator.models.IntentType

interface ContextRouter {
    suspend fun route(request: ContextRequest): List<ContextLevel>
    fun routeRequirements(intentType: IntentType, input: String): ContextRequirement
}
