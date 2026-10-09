package com.example.jarvis.ai.calendar

interface CalendarProvider {
    suspend fun getEvents(startTime: Long, endTime: Long): List<CalendarEvent>
    suspend fun getEvent(id: String): CalendarEvent?
    suspend fun searchEvents(query: String): List<CalendarEvent>
    suspend fun createEvent(event: CalendarEvent): CalendarEvent
    suspend fun updateEvent(event: CalendarEvent): CalendarEvent
    suspend fun cancelEvent(id: String): Boolean
}

class MockCalendarProvider : CalendarProvider {
    private val eventsStore = mutableMapOf<String, CalendarEvent>()

    init {
        val now = System.currentTimeMillis()
        val sample = CalendarEvent(
            id = "event_1",
            title = "Project Sync",
            description = "Review JARVIS milestones",
            startTime = now + 3600000L,
            endTime = now + 7200000L,
            location = "Virtual Room"
        )
        eventsStore[sample.id] = sample
    }

    override suspend fun getEvents(startTime: Long, endTime: Long): List<CalendarEvent> {
        return eventsStore.values.filter { it.status != CalendarStatus.CANCELLED }
    }

    override suspend fun getEvent(id: String): CalendarEvent? {
        return eventsStore[id]
    }

    override suspend fun searchEvents(query: String): List<CalendarEvent> {
        return eventsStore.values.filter {
            (it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)) &&
                    it.status != CalendarStatus.CANCELLED
        }
    }

    override suspend fun createEvent(event: CalendarEvent): CalendarEvent {
        eventsStore[event.id] = event
        return event
    }

    override suspend fun updateEvent(event: CalendarEvent): CalendarEvent {
        eventsStore[event.id] = event
        return event
    }

    override suspend fun cancelEvent(id: String): Boolean {
        val existing = eventsStore[id] ?: return false
        eventsStore[id] = existing.copy(status = CalendarStatus.CANCELLED)
        return true
    }
}
