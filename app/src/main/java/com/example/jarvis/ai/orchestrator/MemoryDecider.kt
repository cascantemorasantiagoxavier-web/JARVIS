package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.MemoryDecision
import com.example.jarvis.ai.orchestrator.models.ValidationResult

/**
 * Abstraction responsible for deciding whether and how to store execution outputs into memory.
 */
interface MemoryDecider {
    suspend fun decide(
        input: String,
        output: String,
        validation: ValidationResult
    ): MemoryDecision
}
