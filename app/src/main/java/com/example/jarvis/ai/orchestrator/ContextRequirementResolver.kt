package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.ContextRequirements
import com.example.jarvis.ai.orchestrator.models.DetectedIntent

/**
 * Abstraction responsible for resolving context requirements based on intent.
 */
interface ContextRequirementResolver {
    suspend fun resolveRequirements(intent: DetectedIntent): ContextRequirements
}
