package com.example.jarvis.proactive

import com.example.jarvis.ai.proactive.DefaultProactiveEngine
import com.example.jarvis.ai.proactive.DefaultProactivePolicy
import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventType
import com.example.jarvis.domain.proactive.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProactiveEnginePhase19Test {

    private lateinit var policy: DefaultProactivePolicy
    private lateinit var engine: DefaultProactiveEngine

    @Before
    fun setUp() {
        policy = DefaultProactivePolicy()
        engine = DefaultProactiveEngine(policy)
    }

    @Test
    fun testProactiveDisabledPreference() = runBlocking {
        val event = AppEvent(type = EventType.TASK_OVERDUE, data = mapOf("taskTitle" to "Buy milk"))
        val prefs = ProactivePreferences(enabled = false)
        val suggestions = engine.processEvent(event, prefs).toList()
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun testProactiveTaskOverdueSuggestion() = runBlocking {
        val event = AppEvent(type = EventType.TASK_OVERDUE, data = mapOf("taskId" to "1", "taskTitle" to "Submit report"))
        val prefs = ProactivePreferences(enabled = true)
        val suggestions = engine.processEvent(event, prefs).toList()
        assertEquals(1, suggestions.size)
        assertEquals("Task Overdue", suggestions[0].title)
        assertEquals(ProactiveReason.OVERDUE_TASK, suggestions[0].reason)
    }

    @Test
    fun testProactiveCooldownEnforcement() = runBlocking {
        val event = AppEvent(type = EventType.TASK_OVERDUE, data = mapOf("taskId" to "1", "taskTitle" to "Submit report"))
        val prefs = ProactivePreferences(enabled = true, cooldownMinutesPerKey = 60L)

        // First event
        val firstBatch = engine.processEvent(event, prefs).toList()
        assertEquals(1, firstBatch.size)

        // Immediate duplicate event should be suppressed by cooldown
        val secondBatch = engine.processEvent(event, prefs).toList()
        assertTrue(secondBatch.isEmpty())
    }

    @Test
    fun testProactivePreferencesFeatureToggle() = runBlocking {
        val event = AppEvent(type = EventType.TASK_OVERDUE, data = mapOf("taskId" to "2", "taskTitle" to "Review code"))
        val prefs = ProactivePreferences(enabled = true, allowDeadlineWarnings = false)
        // Note: OVERDUE_TASK maps to allowDeadlineWarnings check in default policy
        val suggestions = engine.processEvent(event, prefs).toList()
        assertTrue(suggestions.isEmpty())
    }
}
