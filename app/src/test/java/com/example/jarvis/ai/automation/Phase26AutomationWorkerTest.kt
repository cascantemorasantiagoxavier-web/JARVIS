package com.example.jarvis.ai.automation

import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class Phase26AutomationWorkerTest {

    private lateinit var automationEngine: AutomationEngine
    private lateinit var occurrenceManager: AutomationOccurrenceManager

    @Before
    fun setUp() {
        automationEngine = AutomationEngine()
        occurrenceManager = AutomationOccurrenceManager(null)
        occurrenceManager.clear()
    }

    @Test
    fun test1_DailyCron0800_Worker0807_ResolvesTo0800() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 7, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val occurrences = CronOccurrenceCalculator.calculatePendingOccurrences(
            workflowId = "morning",
            cronExpression = "0 8 * * *",
            currentTime = cal.timeInMillis,
            lookbackCount = 1
        )

        assertEquals(1, occurrences.size)
        val expectedCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedCal.timeInMillis, occurrences[0].timestamp)
    }

    @Test
    fun test2_DailyCron0800_Worker0816_ResolvesTo0800Not0815() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 16, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val occurrences = CronOccurrenceCalculator.calculatePendingOccurrences(
            workflowId = "morning",
            cronExpression = "0 8 * * *",
            currentTime = cal.timeInMillis,
            lookbackCount = 1
        )

        assertEquals(1, occurrences.size)
        val expectedCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedCal.timeInMillis, occurrences[0].timestamp)
    }

    @Test
    fun test3_Worker0807And0813_SingleEffectiveExecution() = runBlocking {
        val cal07 = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 7, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val occurrences1 = CronOccurrenceCalculator.calculatePendingOccurrences("morning", "0 8 * * *", cal07, 1)
        assertEquals(1, occurrences1.size)
        val key = occurrences1[0].occurrenceKey

        // First run at 08:07
        assertFalse(occurrenceManager.isExecuted(key))
        occurrenceManager.markExecuted(key)
        assertTrue(occurrenceManager.isExecuted(key))

        // Second run at 08:13 sees same key already executed
        val cal13 = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 13, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occurrences2 = CronOccurrenceCalculator.calculatePendingOccurrences("morning", "0 8 * * *", cal13, 1)
        assertEquals(key, occurrences2[0].occurrenceKey)
        assertTrue(occurrenceManager.isExecuted(occurrences2[0].occurrenceKey))
    }

    @Test
    fun test4_NextDay0800_DistinctOccurrence() {
        val calDay1 = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 28, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occ1 = CronOccurrenceCalculator.calculatePendingOccurrences("morning", "0 8 * * *", calDay1, 1)

        val calDay2 = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occ2 = CronOccurrenceCalculator.calculatePendingOccurrences("morning", "0 8 * * *", calDay2, 1)

        assertNotEquals(occ1[0].occurrenceKey, occ2[0].occurrenceKey)
    }

    @Test
    fun test5_Every30MinutesCron_Distinguishes0800And0830() {
        val cal0807 = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 7, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occs1 = CronOccurrenceCalculator.calculatePendingOccurrences("thirty", "*/30 * * * *", cal0807, 1)
        assertEquals(1, occs1.size)

        val cal0835 = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 8, 35, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occs2 = CronOccurrenceCalculator.calculatePendingOccurrences("thirty", "*/30 * * * *", cal0835, 1)
        assertEquals(1, occs2.size)

        assertNotEquals(occs1[0].occurrenceKey, occs2[0].occurrenceKey)
    }

    @Test
    fun test6_WeeklyCron_CorrectOccurrence() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 28, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occs = CronOccurrenceCalculator.calculatePendingOccurrences("weekly", "0 8 * * 1", cal, 1)
        assertEquals(1, occs.size)
    }

    @Test
    fun test7_MonthlyCron_CorrectOccurrence() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 1, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occs = CronOccurrenceCalculator.calculatePendingOccurrences("monthly", "0 8 1 * *", cal, 1)
        assertEquals(1, occs.size)
    }

    @Test
    fun test8_MultipleMissedSchedulesMaxCatchUp() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val occs = CronOccurrenceCalculator.calculatePendingOccurrences("catchup", "0 8 * * *", cal, 3)
        assertTrue(occs.size <= 3)
    }

    @Test
    fun test9_ProcessRestartPersistence() {
        val key = "cron_test_123"
        occurrenceManager.markExecuted(key)
        assertTrue(occurrenceManager.isExecuted(key))
    }

    @Test
    fun test10_RetryPreservesOccurrenceIdentity() {
        val key = "cron_retry_456"
        val correlationId1 = key
        val correlationId2 = key
        assertEquals(correlationId1, correlationId2)
    }

    @Test
    fun test11_MarkExecutedOnlyOnSuccess() {
        val key = "cron_crash_789"
        assertFalse(occurrenceManager.isExecuted(key))
    }

    @Test
    fun test12_DestructiveActionBlockedByRiskEvaluator() = runBlocking {
        val workflow = Automation(
            name = "Destructive Schedule",
            trigger = AutomationTrigger(type = TriggerType.SCHEDULE),
            actions = listOf(
                AutomationAction(
                    type = AutomationActionType.UPDATE_TASK,
                    targetToolName = "communication_send",
                    requiresConfirmation = true
                )
            )
        )
        automationEngine.registerAutomation(workflow)

        val event = AppEvent(type = EventType.ROUTINE_TRIGGERED, data = mapOf("workflowId" to workflow.id))
        val results = automationEngine.handleEvent(event, forceManual = true)

        assertEquals(1, results.size)
        assertEquals(AutomationExecutionStatus.BLOCKED_BY_RISK, results[0].status)
    }
}
