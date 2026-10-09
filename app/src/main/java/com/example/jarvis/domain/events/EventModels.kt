package com.example.jarvis.domain.events

import java.util.UUID

/**
 * Standard internal event types.
 */
enum class EventType {
    TASK_OVERDUE,
    TASK_COMPLETED,
    DEADLINE_APPROACHING,
    PROJECT_UPDATED,
    RESEARCH_COMPLETED,
    NOTE_CHANGED,
    COMMUNICATION_RECEIVED,
    CALENDAR_EVENT_CREATED,
    CALENDAR_EVENT_STARTING,
    ROUTINE_TRIGGERED,
    CUSTOM
}

/**
 * Event source origins.
 */
enum class EventSource {
    SYSTEM,
    USER,
    OBSIDIAN,
    CALENDAR,
    ROUTINE_ENGINE,
    PRODUCTIVITY,
    RESEARCH,
    COMMUNICATIONS,
    PROACTIVE,
    AUTOMATION
}

data class EventMetadata(
    val priority: Int = 0,
    val tags: List<String> = emptyList()
)

/**
 * Decoupled internal application event model.
 */
data class AppEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: EventType,
    val source: EventSource = EventSource.SYSTEM,
    val correlationId: String = UUID.randomUUID().toString(),
    val causationId: String? = null,
    val data: Map<String, String> = emptyMap(),
    val payload: Map<String, Any> = emptyMap(),
    val metadata: EventMetadata = EventMetadata(),
    val timestamp: Long = System.currentTimeMillis()
)

data class EventSubscription(
    val id: String = UUID.randomUUID().toString(),
    val eventType: EventType?,
    val handlerName: String,
    val priority: Int = 0,
    val handler: suspend (AppEvent) -> Unit
)

data class EventResult(
    val eventId: String,
    val handlerName: String,
    val isSuccess: Boolean,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
