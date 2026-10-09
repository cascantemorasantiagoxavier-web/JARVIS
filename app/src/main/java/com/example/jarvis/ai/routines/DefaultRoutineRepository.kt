package com.example.jarvis.ai.routines

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.routines.Routine
import com.example.jarvis.domain.routines.RoutineRepository

/**
 * In-memory local implementation of RoutineRepository.
 */
class DefaultRoutineRepository : RoutineRepository {

    private val routinesStore = mutableMapOf<String, Routine>()

    override suspend fun saveRoutine(routine: Routine): AppResult<Routine> {
        routinesStore[routine.id] = routine
        return AppResult.Success(routine)
    }

    override suspend fun getRoutine(id: String): AppResult<Routine?> {
        return AppResult.Success(routinesStore[id])
    }

    override suspend fun listRoutines(enabledOnly: Boolean): AppResult<List<Routine>> {
        val filtered = routinesStore.values.filter { routine ->
            !enabledOnly || routine.isEnabled
        }
        return AppResult.Success(filtered)
    }

    override suspend fun deleteRoutine(id: String, isDestructiveConfirmed: Boolean): AppResult<Boolean> {
        if (!isDestructiveConfirmed) {
            return AppResult.Error(
                SecurityException("Destructive action 'delete routine' requires explicit user confirmation.")
            )
        }
        val removed = routinesStore.remove(id) != null
        return AppResult.Success(removed)
    }
}
