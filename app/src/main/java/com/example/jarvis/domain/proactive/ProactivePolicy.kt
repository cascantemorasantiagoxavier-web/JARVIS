package com.example.jarvis.domain.proactive

/**
 * Interface defining anti-spam, cooldown, quiet hours, and preference evaluation for proactive suggestions.
 */
interface ProactivePolicy {
    fun evaluateSuggestion(
        suggestion: ProactiveSuggestion,
        preferences: ProactivePreferences,
        recentSuggestions: List<ProactiveSuggestion>,
        currentTime: Long = System.currentTimeMillis()
    ): ProactiveDecision
}
