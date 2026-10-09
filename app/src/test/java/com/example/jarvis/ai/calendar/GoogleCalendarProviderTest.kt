package com.example.jarvis.ai.calendar

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GoogleCalendarProviderTest {

    private lateinit var provider: GoogleCalendarProvider

    @Before
    fun setUp() {
        provider = GoogleCalendarProvider()
    }

    @Test(expected = SecurityException::class)
    fun testUnauthenticatedAccessThrowsSecurityException() {
        runBlocking {
            provider.getEvents(System.currentTimeMillis(), System.currentTimeMillis() + 3600000L)
        }
    }

    @Test
    fun testAuthenticationAndOperations() = runBlocking {
        val token = GoogleOAuthToken(accessToken = "sample_oauth_token_123", expiresIn = 3600L)
        provider.authenticateWithOAuth(token)

        assertTrue(provider.isAuthenticated())

        val event = CalendarEvent(title = "Google Calendar Sync Test", startTime = System.currentTimeMillis(), endTime = System.currentTimeMillis() + 1800000L)
        val created = provider.createEvent(event)

        assertEquals("google_calendar", created.source)
        assertEquals("Google Calendar Sync Test", created.title)
    }

    @Test
    fun testTokenExpirationCheck() {
        val expiredToken = GoogleOAuthToken(accessToken = "old_token", expiresIn = -10L)
        provider.authenticateWithOAuth(expiredToken)

        assertFalse(provider.isAuthenticated())
    }
}
