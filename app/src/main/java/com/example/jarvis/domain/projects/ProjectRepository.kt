package com.example.jarvis.domain.projects

import com.example.jarvis.core.common.AppResult

/**
 * Domain repository interface for Project management.
 */
interface ProjectRepository {
    suspend fun create(project: Project): AppResult<Project>
    suspend fun get(id: String): AppResult<Project?>
    suspend fun update(project: Project): AppResult<Project>
    suspend fun delete(id: String, isDestructiveConfirmed: Boolean = false): AppResult<Boolean>
    suspend fun list(status: ProjectStatus? = null): AppResult<List<Project>>
}
