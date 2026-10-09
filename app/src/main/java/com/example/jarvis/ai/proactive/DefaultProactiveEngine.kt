package com.example.jarvis.ai.proactive

import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventType
import com.example.jarvis.domain.proactive.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Implementation of ProactiveEngine executing the 8-step proactive pipeline.
 */
class DefaultProactiveEngine(
    private val policy: ProactivePolicy = DefaultProactivePolicy()
) : ProactiveEngine {

    private val history = mutableListOf<ProactiveSuggestion>()

    override fun processEvent(
        event: AppEvent,
        preferences: ProactivePreferences
    ): Flow<ProactiveSuggestion> = flow {
        val candidate = mapEventToCandidateSuggestion(event) ?: return@flow

        val evaluatedDecision = policy.evaluateSuggestion(
            suggestion = candidate,
            preferences = preferences,
            recentSuggestions = history,
            currentTime = event.timestamp
        )

        val finalSuggestion = candidate.copy(decision = evaluatedDecision)
        history.add(finalSuggestion)

        if (finalSuggestion.decision != ProactiveDecision.IGNORE) {
            emit(finalSuggestion)
        }
    }

    private fun mapEventToCandidateSuggestion(event: AppEvent): ProactiveSuggestion? {
        return when (event.type) {
            EventType.TASK_OVERDUE -> ProactiveSuggestion(
                title = "Task Overdue",
                message = "The task '${event.data["taskTitle"] ?: "Task"}' is overdue.",
                priority = ProactivePriority.HIGH,
                decision = ProactiveDecision.SUGGEST,
                reason = ProactiveReason.OVERDUE_TASK,
                deduplicationKey = "overdue_${event.data["taskId"] ?: "unknown"}"
            )
            EventType.DEADLINE_APPROACHING -> ProactiveSuggestion(
                title = "Deadline Approaching",
                message = "Deadline approaching for '${event.data["title"] ?: "Item"}'.",
                priority = ProactivePriority.HIGH,
                decision = ProactiveDecision.SUGGEST,
                reason = ProactiveReason.DEADLINE_WARNING,
                deduplicationKey = "deadline_${event.data["id"] ?: "unknown"}"
            )
            EventType.ROUTINE_TRIGGERED -> ProactiveSuggestion(
                title = "Routine Reminder",
                message = "Time to start '${event.data["routineName"] ?: "Routine"}'.",
                priority = ProactivePriority.MEDIUM,
                decision = ProactiveDecision.SUGGEST,
                reason = ProactiveReason.ROUTINE_SUGGESTION,
                deduplicationKey = "routine_${event.data["routineId"] ?: "unknown"}"
            )
            else -> null
        }
    }
}
