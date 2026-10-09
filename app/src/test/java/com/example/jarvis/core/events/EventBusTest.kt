package com.example.jarvis.core.events

import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventSubscription
import com.example.jarvis.domain.events.EventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventBusTest {

    private lateinit var eventBus: RobustEventBus

    @Before
    fun setUp() {
        eventBus = RobustEventBus()
    }

    @Test
    fun testPublishAndSubscribe() = runBlocking {
        var received = false
        eventBus.subscribe(
            EventSubscription(
                eventType = EventType.TASK_COMPLETED,
                handlerName = "TestHandler",
                handler = { received = true }
            )
        )

        val event = AppEvent(type = EventType.TASK_COMPLETED)
        eventBus.publish(event)

        assertTrue(received)
    }

    @Test
    fun testErrorIsolationBetweenHandlers() = runBlocking {
        var handler2Executed = false
        eventBus.subscribe(
            EventSubscription(
                eventType = EventType.TASK_OVERDUE,
                handlerName = "FailingHandler",
                priority = 10,
                handler = { throw RuntimeException("Handler failure") }
            )
        )
        eventBus.subscribe(
            EventSubscription(
                eventType = EventType.TASK_OVERDUE,
                handlerName = "SurvivingHandler",
                priority = 5,
                handler = { handler2Executed = true }
            )
        )

        val event = AppEvent(type = EventType.TASK_OVERDUE)
        eventBus.publish(event)

        assertTrue(handler2Executed)
    }

    @Test
    fun testIdempotencySuppression() = runBlocking {
        var executionCount = 0
        eventBus.subscribe(
            EventSubscription(
                eventType = EventType.NOTE_CHANGED,
                handlerName = "CountHandler",
                handler = { executionCount++ }
            )
        )

        val event = AppEvent(type = EventType.NOTE_CHANGED)
        eventBus.publish(event)
        eventBus.publish(event)

        assertEquals(1, executionCount)
    }
}
