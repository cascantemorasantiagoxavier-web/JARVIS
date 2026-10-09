package com.example.jarvis.ai.proactive

import com.example.jarvis.domain.proactive.*
import java.util.Calendar

/**
 * Anti-spam, cooldown, quiet hours, and preference policy implementation.
 */
class DefaultProactivePolicy : ProactivePolicy {

    override fun evaluateSuggestion(
        suggestion: ProactiveSuggestion,
        preferences: ProactivePreferences,
        recentSuggestions: List<ProactiveSuggestion>,
        currentTime: Long
    ): ProactiveDecision {
        // 1. Proactivity master toggle
        if (!preferences.enabled) {
            return ProactiveDecision.IGNORE
        }

        // 2. Specific feature toggle check
        val allowedByFeature = when (suggestion.reason) {
            ProactiveReason.DEADLINE_WARNING, ProactiveReason.OVERDUE_TASK -> preferences.allowDeadlineWarnings
            ProactiveReason.IDLE_NEXT_ACTION -> preferences.allowTaskSuggestions
            ProactiveReason.ROUTINE_SUGGESTION -> preferences.allowRoutineSuggestions
            ProactiveReason.DUPLICATE_PREVENTION -> true
        }
        if (!allowedByFeature) {
            return ProactiveDecision.IGNORE
        }

        // 3. Cooldown per deduplication key
        val cooldownMs = preferences.cooldownMinutesPerKey * 60 * 1000
        val lastMatching = recentSuggestions.find {
            it.deduplicationKey.equals(suggestion.deduplicationKey, ignoreCase = true)
        }
        if (lastMatching != null && (currentTime - lastMatching.timestamp) < cooldownMs) {
            return ProactiveDecision.IGNORE
        }

        // 4. Daily maximum limit check
        val oneDayAgo = currentTime - (24 * 60 * 60 * 1000)
        val suggestionsInLast24h = recentSuggestions.count {
            it.timestamp >= oneDayAgo && it.decision != ProactiveDecision.IGNORE
        }
        if (suggestionsInLast24h >= preferences.maxNotificationsPerDay && suggestion.priority != ProactivePriority.URGENT) {
            return ProactiveDecision.QUEUE
        }

        // 5. Quiet hours check
        val calendar = Calendar.getInstance().apply { timeInMillis = currentTime }
        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val isQuietHours = if (preferences.quietHoursStart > preferences.quietHoursEnd) {
            hourOfDay >= preferences.quietHoursStart || hourOfDay < preferences.quietHoursEnd
        } else {
            hourOfDay >= preferences.quietHoursStart && hourOfDay < preferences.quietHoursEnd
        }

        if (isQuietHours && suggestion.priority != ProactivePriority.URGENT) {
            return ProactiveDecision.QUEUE
        }

        // 6. Approved
        return suggestion.decision
    }
}
