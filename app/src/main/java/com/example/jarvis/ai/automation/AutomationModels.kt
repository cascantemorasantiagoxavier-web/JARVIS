package com.example.jarvis.ai.automation

import java.util.UUID

enum class TriggerType {
    EVENT,
    SCHEDULE,
    MANUAL
}

enum class ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    GREATER_THAN,
    LESS_THAN,
    EXISTS
}

data class AutomationTrigger(
    val type: TriggerType,
    val eventType: String? = null,
    val scheduleCron: String? = null,
    val customPredicate: (Map<String, Any>) -> Boolean = { true }
)

data class AutomationCondition(
    val field: String,
    val operator: ConditionOperator,
    val expectedValue: Any? = null
) {
    fun evaluate(data: Map<String, Any>): Boolean {
        val actual = data[field]
        return when (operator) {
            ConditionOperator.EQUALS -> actual == expectedValue
            ConditionOperator.NOT_EQUALS -> actual != expectedValue
            ConditionOperator.CONTAINS -> actual?.toString()?.contains(expectedValue?.toString() ?: "", ignoreCase = true) == true
            ConditionOperator.GREATER_THAN -> {
                val act = (actual as? Number)?.toDouble() ?: 0.0
                val exp = (expectedValue as? Number)?.toDouble() ?: 0.0
                act > exp
            }
            ConditionOperator.LESS_THAN -> {
                val act = (actual as? Number)?.toDouble() ?: 0.0
                val exp = (expectedValue as? Number)?.toDouble() ?: 0.0
                act < exp
            }
            ConditionOperator.EXISTS -> actual != null
        }
    }
}

enum class AutomationActionType {
    CREATE_TASK,
    UPDATE_TASK,
    CREATE_NOTE,
    UPDATE_NOTE,
    CREATE_PROJECT_ACTION,
    SEND_NOTIFICATION,
    RUN_AGENT
}

data class AutomationAction(
    val type: AutomationActionType,
    val targetToolName: String,
    val parameters: Map<String, Any> = emptyMap(),
    val agentId: String? = null,
    val requiresConfirmation: Boolean = false
)

enum class AutomationExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    BLOCKED_BY_RISK,
    WAITING_CONFIRMATION
}

data class AutomationExecution(
    val id: String = UUID.randomUUID().toString(),
    val automationId: String,
    val status: AutomationExecutionStatus,
    val output: String,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val trigger: AutomationTrigger,
    val conditions: List<AutomationCondition> = emptyList(),
    val actions: List<AutomationAction> = emptyList(),
    val enabled: Boolean = true,
    val maxDepth: Int = 3,
    val maxActionsPerRun: Int = 5
)
