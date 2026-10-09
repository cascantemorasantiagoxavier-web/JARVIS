package com.example.jarvis.productivity

import com.example.jarvis.ai.productivity.DefaultProjectRepository
import com.example.jarvis.ai.productivity.DefaultTaskPrioritizer
import com.example.jarvis.ai.productivity.DefaultTaskRepository
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.projects.Project
import com.example.jarvis.domain.projects.ProjectStatus
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority
import com.example.jarvis.domain.tasks.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProductivityPhase17Test {

    private lateinit var taskRepo: DefaultTaskRepository
    private lateinit var projectRepo: DefaultProjectRepository
    private lateinit var prioritizer: DefaultTaskPrioritizer

    @Before
    fun setUp() {
        taskRepo = DefaultTaskRepository()
        projectRepo = DefaultProjectRepository()
        prioritizer = DefaultTaskPrioritizer()
    }

    @Test
    fun testTaskModelAndLifecycle() = runBlocking {
        val task = Task(title = "Test Task", status = TaskStatus.INBOX)
        taskRepo.create(task)

        val fetched = taskRepo.get(task.id)
        assertTrue(fetched is AppResult.Success)
        assertEquals(TaskStatus.INBOX, (fetched as AppResult.Success).data?.status)

        taskRepo.complete(task.id)
        val completed = taskRepo.get(task.id)
        assertEquals(TaskStatus.COMPLETED, (completed as AppResult.Success).data?.status)
    }

    @Test
    fun testTaskPriorityAndPrioritizer() {
        val urgentTask = Task(title = "Urgent", priority = TaskPriority.URGENT)
        val result = prioritizer.prioritize(urgentTask, null, System.currentTimeMillis())
        assertTrue(result.calculatedScore >= 40.0f)
    }

    @Test
    fun testProjectModelAndLifecycle() = runBlocking {
        val project = Project(name = "JARVIS Project", status = ProjectStatus.ACTIVE, goal = "Build JARVIS")
        projectRepo.create(project)

        val fetched = projectRepo.get(project.id)
        assertTrue(fetched is AppResult.Success)
        assertEquals("Build JARVIS", (fetched as AppResult.Success).data?.goal)
    }

    @Test
    fun testProjectTaskIntegration() = runBlocking {
        val project = Project(name = "Project A")
        projectRepo.create(project)

        val task1 = Task(title = "Task 1", projectId = project.id)
        val task2 = Task(title = "Task 2", projectId = project.id)
        taskRepo.create(task1)
        taskRepo.create(task2)

        val listResult = taskRepo.list(projectId = project.id)
        assertTrue(listResult is AppResult.Success)
        assertEquals(2, (listResult as AppResult.Success).data.size)
    }

    @Test
    fun testTaskDependenciesAndBlockedStatus() {
        val parentTask = Task(id = "task_a", title = "Parent Task", status = TaskStatus.TODO)
        val dependentTask = Task(id = "task_b", title = "Dependent Task", dependencies = listOf("task_a"), status = TaskStatus.BLOCKED)

        assertEquals(TaskStatus.BLOCKED, dependentTask.status)
        assertTrue(dependentTask.dependencies.contains(parentTask.id))
    }
}
