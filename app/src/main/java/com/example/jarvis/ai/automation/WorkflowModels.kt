package com.example.jarvis.ai.automation

import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventType
import java.util.UUID

data class Trigger(
    val id: String = UUID.randomUUID().toString(),
    val eventType: EventType,
    val filterPredicate: (AppEvent) -> Boolean = { true }
)

data class Condition(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val check: () -> Boolean
)

data class WorkflowStep(
    val stepNumber: Int,
    val agentId: String,
    val toolName: String? = null,
    val parameters: Map<String, Any> = emptyMap()
)

data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val steps: List<WorkflowStep>
)

data class WorkflowExecutionResult(
    val workflowId: String,
    val workflowName: String,
    val isSuccess: Boolean,
    val output: String,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
