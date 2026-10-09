package com.example.jarvis.ai.automation

import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutomationEngineTest {

    private lateinit var engine: AutomationEngine

    @Before
    fun setUp() {
        engine = AutomationEngine()
    }

    @Test
    fun testAutomationTriggerAndExecution() = runBlocking {
        val automation = Automation(
            name = "System Info Automation",
            trigger = AutomationTrigger(type = TriggerType.EVENT, eventType = EventType.TASK_COMPLETED.name),
            conditions = listOf(AutomationCondition(field = "taskId", operator = ConditionOperator.EXISTS)),
            actions = listOf(AutomationAction(type = AutomationActionType.RUN_AGENT, targetToolName = "system_info"))
        )
        engine.registerAutomation(automation)

        val event = AppEvent(type = EventType.TASK_COMPLETED, data = mapOf("taskId" to "123"))
        val results = engine.handleEvent(event)

        assertEquals(1, results.size)
        assertEquals(AutomationExecutionStatus.SUCCESS, results[0].status)
    }

    @Test
    fun testAutomationConditionsFail() = runBlocking {
        val automation = Automation(
            name = "Conditional Automation",
            trigger = AutomationTrigger(type = TriggerType.EVENT, eventType = EventType.TASK_OVERDUE.name),
            conditions = listOf(AutomationCondition(field = "status", operator = ConditionOperator.EQUALS, expectedValue = "urgent")),
            actions = listOf(AutomationAction(type = AutomationActionType.RUN_AGENT, targetToolName = "system_info"))
        )
        engine.registerAutomation(automation)

        val event = AppEvent(type = EventType.TASK_OVERDUE, data = mapOf("status" to "normal"))
        val results = engine.handleEvent(event)

        assertTrue(results.isEmpty())
    }

    @Test
    fun testAutomationIdempotency() = runBlocking {
        val automation = Automation(
            name = "Idempotency Test",
            trigger = AutomationTrigger(type = TriggerType.EVENT, eventType = EventType.NOTE_CHANGED.name),
            actions = listOf(AutomationAction(type = AutomationActionType.RUN_AGENT, targetToolName = "system_info"))
        )
        engine.registerAutomation(automation)

        val event = AppEvent(type = EventType.NOTE_CHANGED)
        val res1 = engine.handleEvent(event)
        val res2 = engine.handleEvent(event)

        assertEquals(1, res1.size)
        assertTrue(res2.isEmpty())
    }
}
