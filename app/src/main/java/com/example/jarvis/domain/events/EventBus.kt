package com.example.jarvis.domain.events

import kotlinx.coroutines.flow.Flow

/**
 * Decoupled event bus interface for internal reactive events.
 */
interface EventBus {
    suspend fun publish(event: AppEvent)
    fun subscribe(eventType: EventType? = null): Flow<AppEvent>
}
