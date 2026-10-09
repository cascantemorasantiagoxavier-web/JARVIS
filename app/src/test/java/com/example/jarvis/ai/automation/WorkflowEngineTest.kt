package com.example.jarvis.ai.automation

import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkflowEngineTest {

    private lateinit var engine: WorkflowEngine

    @Before
    fun setUp() {
        engine = WorkflowEngine()
    }

    @Test
    fun testWorkflowExecutionOnEvent() = runBlocking {
        val wf = Workflow(
            name = "Test Workflow",
            trigger = Trigger(eventType = EventType.TASK_COMPLETED),
            conditions = listOf(Condition(description = "Always true") { true }),
            steps = listOf(WorkflowStep(stepNumber = 1, agentId = "device_agent", toolName = "system_info"))
        )
        engine.registerWorkflow(wf)

        val event = AppEvent(type = EventType.TASK_COMPLETED, data = mapOf("taskId" to "123"))
        val results = engine.handleEvent(event)

        assertEquals(1, results.size)
        assertTrue(results[0].isSuccess)
    }

    @Test
    fun testWorkflowConditionsNotMet() = runBlocking {
        val wf = Workflow(
            name = "Conditional Workflow",
            trigger = Trigger(eventType = EventType.TASK_OVERDUE),
            conditions = listOf(Condition(description = "Always false") { false }),
            steps = listOf(WorkflowStep(stepNumber = 1, agentId = "device_agent", toolName = "system_info"))
        )
        engine.registerWorkflow(wf)

        val event = AppEvent(type = EventType.TASK_OVERDUE)
        val results = engine.handleEvent(event)

        assertTrue(results.isEmpty())
    }
}
