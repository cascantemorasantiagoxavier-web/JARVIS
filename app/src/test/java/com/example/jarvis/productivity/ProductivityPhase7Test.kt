package com.example.jarvis.productivity

import com.example.jarvis.ai.planning.DefaultPlanningEngine
import com.example.jarvis.ai.proactive.DefaultProactiveEngine
import com.example.jarvis.ai.proactive.DefaultProactivePolicy
import com.example.jarvis.ai.productivity.DefaultProjectRepository
import com.example.jarvis.ai.productivity.DefaultTaskPrioritizer
import com.example.jarvis.ai.productivity.DefaultTaskRepository
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.core.events.DefaultEventBus
import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventSource
import com.example.jarvis.domain.events.EventType
import com.example.jarvis.domain.planning.Goal
import com.example.jarvis.domain.proactive.*
import com.example.jarvis.domain.projects.Project
import com.example.jarvis.domain.projects.ProjectStatus
import com.example.jarvis.domain.tasks.Task
import com.example.jarvis.domain.tasks.TaskPriority
import com.example.jarvis.domain.tasks.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class ProductivityPhase7Test {

    private lateinit var taskRepository: DefaultTaskRepository
    private lateinit var projectRepository: DefaultProjectRepository
    private lateinit var prioritizer: DefaultTaskPrioritizer
    private lateinit var planningEngine: DefaultPlanningEngine
    private lateinit var eventBus: DefaultEventBus
    private lateinit var proactivePolicy: DefaultProactivePolicy
    private lateinit var proactiveEngine: DefaultProactiveEngine

    @Before
    fun setUp() {
        taskRepository = DefaultTaskRepository()
        projectRepository = DefaultProjectRepository()
        prioritizer = DefaultTaskPrioritizer()
        planningEngine = DefaultPlanningEngine()
        eventBus = DefaultEventBus()
        proactivePolicy = DefaultProactivePolicy()
        proactiveEngine = DefaultProactiveEngine(proactivePolicy)
    }

    @Test
    fun testTaskRepositoryCrudAndDestructiveProtection() = runBlocking {
        val task = Task(title = "Review JARVIS Phase 7", priority = TaskPriority.HIGH)
        val createResult = taskRepository.create(task)
        assertTrue(createResult is AppResult.Success)

        val unconfirmedDelete = taskRepository.delete(task.id, isDestructiveConfirmed = false)
        assertTrue("Unconfirmed destructive delete must fail", unconfirmedDelete is AppResult.Error)

        val confirmedDelete = taskRepository.delete(task.id, isDestructiveConfirmed = true)
        assertTrue("Confirmed destructive delete must succeed", confirmedDelete is AppResult.Success)
        assertTrue((confirmedDelete as AppResult.Success).data)
    }

    @Test
    fun testTaskRepositoryCompleteAndRestore() = runBlocking {
        val task = Task(title = "Implement event bus")
        taskRepository.create(task)

        val completeResult = taskRepository.complete(task.id)
        assertTrue(completeResult is AppResult.Success)
        assertEquals(TaskStatus.COMPLETED, (completeResult as AppResult.Success).data.status)
        assertTrue((completeResult as AppResult.Success).data.isCompleted)

        val restoreResult = taskRepository.restore(task.id)
        assertTrue(restoreResult is AppResult.Success)
        assertEquals(TaskStatus.TODO, (restoreResult as AppResult.Success).data.status)
        assertFalse((restoreResult as AppResult.Success).data.isCompleted)
    }

    @Test
    fun testTaskPrioritizerCalculationsAndEdgeCases() {
        val now = System.currentTimeMillis()

        // Task 1: No deadline
        val taskNoDeadline = Task(title = "Task without deadline", priority = TaskPriority.LOW)
        val result1 = prioritizer.prioritize(taskNoDeadline, currentTime = now)
        assertFalse(result1.isOverdue)
        assertEquals(10.0f, result1.breakdown["basePriorityWeight"])

        // Task 2: Overdue task
        val taskOverdue = Task(
            title = "Overdue critical task",
            priority = TaskPriority.URGENT,
            dueDate = now - (3600 * 1000) // 1 hour ago
        )
        val result2 = prioritizer.prioritize(taskOverdue, currentTime = now)
        assertTrue(result2.isOverdue)
        assertEquals(40.0f, result2.breakdown["deadlineWeight"])
        assertTrue(result2.calculatedScore > 70.0f)

        // Task 3: Blocked project penalty
        val blockedProject = Project(name = "Blocked Alpha", status = ProjectStatus.BLOCKED)
        val taskInBlockedProject = Task(title = "Blocked task", projectId = blockedProject.id)
        val result3 = prioritizer.prioritize(taskInBlockedProject, project = blockedProject, currentTime = now)
        assertEquals(-20.0f, result3.breakdown["projectFactor"])
    }

    @Test
    fun testPlanningEngineGoalDecompositionAndNextActions() = runBlocking {
        val goal = Goal(title = "Launch JARVIS Productivity Engine")
        val planResult = planningEngine.generatePlan(goal)

        assertTrue(planResult is AppResult.Success)
        val plan = (planResult as AppResult.Success).data
        assertEquals(3, plan.steps.size)
        assertTrue(plan.nextActions.isNotEmpty())
    }

    @Test
    fun testEventBusPublishAndSubscribe() = runBlocking {
        val testEvent = AppEvent(
            type = EventType.TASK_COMPLETED,
            source = EventSource.USER,
            data = mapOf("title" to "New Task")
        )

        eventBus.publish(testEvent)
        val received = eventBus.subscribe(EventType.TASK_COMPLETED).first()
        assertEquals(EventType.TASK_COMPLETED, received.type)
        assertEquals("New Task", received.data["title"])
    }

    @Test
    fun testProactivePolicyDisabledSuppressesSuggestions() {
        val suggestion = ProactiveSuggestion(
            title = "Task Overdue",
            message = "Task is overdue",
            priority = ProactivePriority.HIGH,
            reason = ProactiveReason.OVERDUE_TASK
        )
        val prefs = ProactivePreferences(enabled = false)
        val decision = proactivePolicy.evaluateSuggestion(suggestion, prefs, emptyList())
        assertEquals(ProactiveDecision.IGNORE, decision)
    }

    @Test
    fun testProactivePolicyCooldownDeduplicationKey() {
        val now = System.currentTimeMillis()
        val suggestion1 = ProactiveSuggestion(
            title = "Deadline Warning",
            message = "Deadline warning",
            reason = ProactiveReason.DEADLINE_WARNING,
            deduplicationKey = "key_deadline_101",
            timestamp = now - (10 * 60 * 1000) // 10 min ago
        )

        val prefs = ProactivePreferences(cooldownMinutesPerKey = 60L)
        val decision = proactivePolicy.evaluateSuggestion(
            suggestion = suggestion1,
            preferences = prefs,
            recentSuggestions = listOf(suggestion1),
            currentTime = now
        )
        assertEquals(ProactiveDecision.IGNORE, decision)
    }

    @Test
    fun testProactivePolicyQuietHoursQueuing() {
        // Set timestamp during quiet hours (e.g. 23:00)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
        }
        val quietTime = calendar.timeInMillis

        val nonUrgentSuggestion = ProactiveSuggestion(
            title = "Routine Check",
            message = "Time for routine check",
            priority = ProactivePriority.MEDIUM,
            reason = ProactiveReason.ROUTINE_SUGGESTION
        )

        val prefs = ProactivePreferences(quietHoursStart = 22, quietHoursEnd = 7)
        val decision = proactivePolicy.evaluateSuggestion(
            suggestion = nonUrgentSuggestion,
            preferences = prefs,
            recentSuggestions = emptyList(),
            currentTime = quietTime
        )
        assertEquals(ProactiveDecision.QUEUE, decision)
    }

    @Test
    fun testProactiveEngineProcessesEvents() = runBlocking {
        val overdueEvent = AppEvent(
            type = EventType.TASK_OVERDUE,
            data = mapOf("taskId" to "t123", "taskTitle" to "Submit Report")
        )

        val suggestions = proactiveEngine.processEvent(overdueEvent).toList()
        assertEquals(1, suggestions.size)
        val suggestion = suggestions[0]
        assertEquals("Task Overdue", suggestion.title)
        assertEquals(ProactiveDecision.SUGGEST, suggestion.decision)
    }
}
