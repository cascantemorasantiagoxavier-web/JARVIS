package com.example.jarvis.ai.calendar

import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.core.security.KeystoreManager

data class GoogleOAuthToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 3600L,
    val scope: String = "https://www.googleapis.com/auth/calendar.events.readonly https://www.googleapis.com/auth/calendar.events",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= (createdAt + (expiresIn * 1000))
}

class GoogleCalendarProvider(
    private val keystoreManager: KeystoreManager? = null,
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) : CalendarProvider {

    private var currentOAuthToken: GoogleOAuthToken? = null

    fun authenticateWithOAuth(token: GoogleOAuthToken) {
        currentOAuthToken = token
        logger.i("GoogleCalendarProvider", "OAuth2 token set successfully")
    }

    fun isAuthenticated(): Boolean {
        return currentOAuthToken != null && !currentOAuthToken!!.isExpired
    }

    override suspend fun getEvents(startTime: Long, endTime: Long): List<CalendarEvent> {
        checkAuth()
        auditLogger.logAction(AuditLogEntry(requestId = "gcal_read", intent = "GOOGLE_CALENDAR_READ", resultSummary = "Fetched events window", isSuccess = true))
        return emptyList()
    }

    override suspend fun getEvent(id: String): CalendarEvent? {
        checkAuth()
        return null
    }

    override suspend fun searchEvents(query: String): List<CalendarEvent> {
        checkAuth()
        auditLogger.logAction(AuditLogEntry(requestId = "gcal_search", intent = "GOOGLE_CALENDAR_SEARCH", resultSummary = "Searched events query '$query'", isSuccess = true))
        return emptyList()
    }

    override suspend fun createEvent(event: CalendarEvent): CalendarEvent {
        checkAuth()
        val created = event.copy(source = "google_calendar")
        auditLogger.logAction(AuditLogEntry(requestId = created.id, intent = "GOOGLE_CALENDAR_CREATE", resultSummary = "Created event '${created.title}'", isSuccess = true))
        return created
    }

    override suspend fun updateEvent(event: CalendarEvent): CalendarEvent {
        checkAuth()
        val updated = event.copy(source = "google_calendar")
        auditLogger.logAction(AuditLogEntry(requestId = updated.id, intent = "GOOGLE_CALENDAR_UPDATE", resultSummary = "Updated event '${updated.title}'", isSuccess = true))
        return updated
    }

    override suspend fun cancelEvent(id: String): Boolean {
        checkAuth()
        auditLogger.logAction(AuditLogEntry(requestId = id, intent = "GOOGLE_CALENDAR_CANCEL", resultSummary = "Cancelled event $id", isSuccess = true))
        return true
    }

    private fun checkAuth() {
        if (!isAuthenticated()) {
            throw SecurityException("Google Calendar OAuth2 authentication required or token expired.")
        }
    }
}
