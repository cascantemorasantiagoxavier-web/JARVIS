package com.example.jarvis.domain.routines

import java.util.UUID

/**
 * Step inside a user routine.
 */
data class RoutineStep(
    val stepOrder: Int,
    val title: String,
    val actionType: String = "notification",
    val estimatedMinutes: Int = 5
)

/**
 * Scheduled routine definition.
 */
data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<RoutineStep> = emptyList(),
    val scheduleTime: String = "08:00",
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5), // Monday..Friday
    val isEnabled: Boolean = true,
    val conditions: Map<String, String> = emptyMap()
)
