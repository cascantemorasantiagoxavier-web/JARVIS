package com.example.jarvis.domain.proactive

import com.example.jarvis.domain.events.AppEvent
import kotlinx.coroutines.flow.Flow

/**
 * Interface for Proactive Engine pipeline processing internal events into proactive suggestions.
 */
interface ProactiveEngine {
    fun processEvent(
        event: AppEvent,
        preferences: ProactivePreferences = ProactivePreferences()
    ): Flow<ProactiveSuggestion>
}
