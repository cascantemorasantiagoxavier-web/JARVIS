package com.example.jarvis.ai.calendar

import java.util.UUID

enum class CalendarStatus {
    TENTATIVE,
    CONFIRMED,
    CANCELLED,
    COMPLETED
}

enum class CalendarOperation {
    READ,
    SEARCH,
    CREATE,
    UPDATE,
    CANCEL
}

data class CalendarEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val timezone: String = "UTC",
    val location: String = "",
    val attendees: List<String> = emptyList(),
    val organizer: String = "",
    val status: CalendarStatus = CalendarStatus.CONFIRMED,
    val projectId: String? = null,
    val taskId: String? = null,
    val source: String = "local"
)

data class CalendarConflict(
    val eventA: CalendarEvent,
    val eventB: CalendarEvent,
    val reason: String = "Time overlap detected"
)
