package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.orchestrator.models.DetectedIntent
import com.example.jarvis.ai.orchestrator.models.ValidationResult

/**
 * Abstraction responsible for validating execution outputs.
 */
interface ResultValidator {
    suspend fun validate(
        input: String,
        result: String,
        intent: DetectedIntent? = null
    ): ValidationResult
}
