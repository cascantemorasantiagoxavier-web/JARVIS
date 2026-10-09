package com.example.jarvis.ai.orchestrator.models

import com.example.jarvis.ai.context.ContextLevel
import com.example.jarvis.domain.agents.Agent
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import java.util.UUID

/**
 * Standardized intent types recognized by the Orchestrator.
 */
enum class IntentType {
    CONVERSATION,
    QUESTION,
    RESEARCH,
    OPEN_APP,
    OPEN_SETTINGS,
    TASK_CREATE,
    TASK_UPDATE,
    TASK_LIST,
    TASK_COMPLETE,
    MEMORY_QUERY,
    MEMORY_WRITE,
    OBSIDIAN_SEARCH,
    OBSIDIAN_READ,
    OBSIDIAN_WRITE,
    PROJECT_QUERY,
    PLANNING,
    DEVICE_INFO,
    CODING,
    DEVICE,
    MEMORY,
    OBSIDIAN,
    PRODUCTIVITY,
    IOT,
    MAPS,
    WEB_SEARCH,
    OPEN_WEB,
    DEVICE_CONTROL,
    DATE_TIME_QUERY,
    CALCULATOR_QUERY,
    BATTERY_QUERY,
    NETWORK_QUERY,
    MEMORY_SAVE,
    MEMORY_DELETE,
    PREFERENCE_UPDATE,
    YOUTUBE_SHORTS,
    CONTACT_SEARCH,
    DAILY_BRIEFING,
    GENERAL,
    UNKNOWN;

    companion object {
        fun fromString(value: String): IntentType {
            val upper = value.uppercase().trim()
            return entries.firstOrNull { it.name == upper } ?: when (upper) {
                "CODE", "KOTLIN" -> CODING
                "SETTINGS", "AJUSTES" -> OPEN_SETTINGS
                "APP", "LAUNCH" -> OPEN_APP
                "TASK", "TODO" -> TASK_CREATE
                "TASKS", "LIST_TASKS" -> TASK_LIST
                "COMPLETE_TASK", "DONE" -> TASK_COMPLETE
                "NOTE", "VAULT" -> OBSIDIAN_SEARCH
                "REMEMBER", "RECALL" -> MEMORY_QUERY
                "SHORT", "SHORTS" -> YOUTUBE_SHORTS
                "CONTACT", "CONTACTS" -> CONTACT_SEARCH
                "BRIEFING", "DAILY" -> DAILY_BRIEFING
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Formal Action Type for JarvisBrain Decision Contract.
 */
enum class ActionType {
    RESPOND,
    USE_TOOL,
    USE_AGENT,
    SEARCH_WEB,
    OPEN_APP,
    ANDROID_ACTION,
    ASK_CLARIFICATION,
    REQUEST_CONFIRMATION,
    EXECUTE_PLAN
}

/**
 * Formal Execution Status for Decisions and Results.
 */
enum class ExecutionStatus {
    SUCCESS,
    FAILURE,
    PARTIAL_SUCCESS,
    NEEDS_PERMISSION,
    NEEDS_CONFIRMATION,
    NEEDS_CLARIFICATION,
    NOT_AVAILABLE
}

/**
 * Categorized error types for structured error handling.
 */
enum class ErrorType {
    VALIDATION_ERROR,
    PERMISSION_DENIED,
    NOT_FOUND,
    TOOL_FAILURE,
    AGENT_FAILURE,
    TIMEOUT,
    NETWORK_ERROR,
    UNKNOWN_ERROR
}

/**
 * Structured error model for Orchestrator processing.
 */
data class OrchestratorError(
    val type: ErrorType,
    val userMessage: String,
    val technicalDetails: String? = null,
    val cause: Throwable? = null
)

/**
 * Audit log entry container for tracking operations without logging sensitive data.
 */
data class AuditLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val requestId: String,
    val intent: String,
    val agentId: String? = null,
    val toolName: String? = null,
    val riskLevel: RiskLevel? = null,
    val inputMetadata: Map<String, String> = emptyMap(),
    val resultSummary: String,
    val isSuccess: Boolean,
    val requiresUserConfirmation: Boolean = false
)

/**
 * Detailed model representing intent detection outcome.
 */
data class DetectedIntent(
    val type: IntentType,
    val rawIntent: String = type.name,
    val confidence: Float = 1.0f,
    val description: String = "",
    val extractedParameters: Map<String, String> = emptyMap()
)

/**
 * Model representing agent selection decision.
 */
data class AgentSelection(
    val agentId: String,
    val agentName: String,
    val reasoning: String = "",
    val targetAgent: Agent? = null
)

/**
 * Model representing tool selection decision.
 */
data class ToolSelection(
    val toolName: String?,
    val reasoning: String = "",
    val parameters: Map<String, Any> = emptyMap(),
    val targetTool: Tool? = null
)

/**
 * Model representing context requirements for intent execution.
 */
data class ContextRequirements(
    val requiredLevels: List<ContextLevel> = listOf(
        ContextLevel.LEVEL_0_RAW_INPUT,
        ContextLevel.LEVEL_1_SESSION_HISTORY
    ),
    val maxTokens: Int = 2048,
    val customKeys: List<String> = emptyList()
)

/**
 * Model representing output result validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val confidenceScore: Float = 1.0f,
    val reasoning: String = "",
    val validationErrors: List<String> = emptyList()
)

/**
 * Memory decision actions.
 */
enum class MemoryAction {
    MEMORIZE,
    SKIP,
    UPDATE,
    DISCARD
}

/**
 * Model representing memory decision abstraction.
 */
data class MemoryDecision(
    val action: MemoryAction,
    val key: String,
    val value: String,
    val reasoning: String = ""
)

/**
 * Request container for the Orchestrator pipeline.
 */
data class OrchestratorRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val input: String,
    val userId: String = "default_user",
    val sessionId: String = "default_session",
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Response container returned by the Orchestrator pipeline.
 */
data class OrchestratorResponse(
    val requestId: String,
    val output: String,
    val isSuccess: Boolean,
    val detectedIntent: DetectedIntent? = null,
    val agentSelection: AgentSelection? = null,
    val toolSelection: ToolSelection? = null,
    val validationResult: ValidationResult? = null,
    val memoryDecision: MemoryDecision? = null,
    val error: OrchestratorError? = null,
    val executionStatus: ExecutionStatus = ExecutionStatus.SUCCESS,
    val metadata: Map<String, Any> = emptyMap()
)
