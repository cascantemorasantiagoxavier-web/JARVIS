package com.example.jarvis.core.events

import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventBus
import com.example.jarvis.domain.events.EventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * SharedFlow implementation of EventBus.
 */
class DefaultEventBus : EventBus {

    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: Flow<AppEvent> = _events.asSharedFlow()

    override suspend fun publish(event: AppEvent) {
        _events.emit(event)
    }

    override fun subscribe(eventType: EventType?): Flow<AppEvent> {
        return if (eventType == null) {
            events
        } else {
            events.filter { it.type == eventType }
        }
    }
}
