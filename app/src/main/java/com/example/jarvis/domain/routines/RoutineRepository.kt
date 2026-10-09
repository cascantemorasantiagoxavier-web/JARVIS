package com.example.jarvis.domain.routines

import com.example.jarvis.core.common.AppResult

/**
 * Domain repository abstraction for Routine management.
 */
interface RoutineRepository {
    suspend fun saveRoutine(routine: Routine): AppResult<Routine>
    suspend fun getRoutine(id: String): AppResult<Routine?>
    suspend fun listRoutines(enabledOnly: Boolean = false): AppResult<List<Routine>>
    suspend fun deleteRoutine(id: String, isDestructiveConfirmed: Boolean = false): AppResult<Boolean>
}
