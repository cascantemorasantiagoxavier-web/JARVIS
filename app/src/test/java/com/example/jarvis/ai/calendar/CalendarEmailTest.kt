package com.example.jarvis.ai.calendar

import com.example.jarvis.ai.email.EmailEngine
import com.example.jarvis.ai.email.EmailMessage
import com.example.jarvis.ai.email.MockEmailProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CalendarEmailTest {

    private lateinit var calendarEngine: CalendarEngine
    private lateinit var emailEngine: EmailEngine

    @Before
    fun setUp() {
        calendarEngine = CalendarEngine(MockCalendarProvider())
        emailEngine = EmailEngine(MockEmailProvider())
    }

    @Test
    fun testCalendarSearchEvents() = runBlocking {
        val events = calendarEngine.search("Project Sync")
        assertTrue(events.isNotEmpty())
        assertEquals("Project Sync", events[0].title)
    }

    @Test
    fun testCalendarConflictDetection() = runBlocking {
        val now = System.currentTimeMillis()
        val e1 = CalendarEvent(title = "Meeting 1", startTime = now, endTime = now + 3600000L)
        val e2 = CalendarEvent(title = "Meeting 2", startTime = now + 1800000L, endTime = now + 5400000L)
        val conflicts = calendarEngine.detectConflicts(listOf(e1, e2))
        assertEquals(1, conflicts.size)
    }

    @Test
    fun testEmailSearchMessages() = runBlocking {
        val messages = emailEngine.search("Project Update")
        assertTrue(messages.isNotEmpty())
        assertEquals("Project Update", messages[0].subject)
    }

    @Test(expected = SecurityException::class)
    fun testEmailSendRequiresConfirmation() {
        runBlocking {
            val msg = EmailMessage(from = "user@jarvis.com", to = "carlos@example.com", subject = "Re: Update", body = "Sure")
            emailEngine.sendWithConfirmation(msg, confirmed = false)
        }
    }

    @Test
    fun testEmailSendWithConfirmationSuccess() = runBlocking {
        val msg = EmailMessage(from = "user@jarvis.com", to = "carlos@example.com", subject = "Re: Update", body = "Sure")
        val success = emailEngine.sendWithConfirmation(msg, confirmed = true)
        assertTrue(success)
    }
}
