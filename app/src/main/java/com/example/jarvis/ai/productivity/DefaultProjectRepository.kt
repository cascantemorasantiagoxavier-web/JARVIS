package com.example.jarvis.ai.productivity

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.projects.Project
import com.example.jarvis.domain.projects.ProjectRepository
import com.example.jarvis.domain.projects.ProjectStatus

/**
 * In-memory local implementation of ProjectRepository.
 */
class DefaultProjectRepository : ProjectRepository {

    private val projectsStore = mutableMapOf<String, Project>()

    override suspend fun create(project: Project): AppResult<Project> {
        projectsStore[project.id] = project
        return AppResult.Success(project)
    }

    override suspend fun get(id: String): AppResult<Project?> {
        return AppResult.Success(projectsStore[id])
    }

    override suspend fun update(project: Project): AppResult<Project> {
        if (!projectsStore.containsKey(project.id)) {
            return AppResult.Error(IllegalArgumentException("Project with ID ${project.id} not found"))
        }
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        projectsStore[project.id] = updated
        return AppResult.Success(updated)
    }

    override suspend fun delete(id: String, isDestructiveConfirmed: Boolean): AppResult<Boolean> {
        if (!isDestructiveConfirmed) {
            return AppResult.Error(
                SecurityException("Destructive action 'delete project' requires explicit user confirmation.")
            )
        }
        val removed = projectsStore.remove(id) != null
        return AppResult.Success(removed)
    }

    override suspend fun list(status: ProjectStatus?): AppResult<List<Project>> {
        val filtered = projectsStore.values.filter { project ->
            status == null || project.status == status
        }
        return AppResult.Success(filtered)
    }
}
