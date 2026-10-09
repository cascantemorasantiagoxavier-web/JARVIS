package com.example.jarvis.ai.calendar

class CalendarEngine(
    private val provider: CalendarProvider = MockCalendarProvider()
) {
    suspend fun getUpcomingEvents(windowMs: Long = 86400000L * 7): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        return provider.getEvents(now, now + windowMs)
    }

    suspend fun search(query: String): List<CalendarEvent> {
        return provider.searchEvents(query)
    }

    suspend fun detectConflicts(events: List<CalendarEvent>): List<CalendarConflict> {
        val conflicts = mutableListOf<CalendarConflict>()
        for (i in events.indices) {
            for (j in i + 1 until events.size) {
                val e1 = events[i]
                val e2 = events[j]
                if (e1.startTime < e2.endTime && e2.startTime < e1.endTime) {
                    conflicts.add(CalendarConflict(e1, e2, "Overlap between '${e1.title}' and '${e2.title}'"))
                }
            }
        }
        return conflicts
    }

    suspend fun create(event: CalendarEvent): CalendarEvent {
        return provider.createEvent(event)
    }

    suspend fun cancel(id: String): Boolean {
        return provider.cancelEvent(id)
    }
}
