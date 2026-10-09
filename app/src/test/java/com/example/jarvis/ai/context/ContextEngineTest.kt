package com.example.jarvis.ai.context

import com.example.jarvis.ai.orchestrator.models.IntentType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContextEngineTest {

    private lateinit var router: DefaultContextRouter
    private lateinit var budget: ContextBudget

    @Before
    fun setUp() {
        router = DefaultContextRouter()
        budget = ContextBudget(maxTokens = 500, reservedTokens = 100)
    }

    @Test
    fun testContextRouterSimpleConversation() {
        val req = router.routeRequirements(IntentType.CONVERSATION, "Hola")
        assertTrue(req.levels.contains(ContextLevel.LEVEL_0_IDENTITY))
        assertTrue(req.levels.contains(ContextLevel.LEVEL_1_SESSION))
        assertFalse(req.needsMemory)
    }

    @Test
    fun testContextRouterMemoryQuery() {
        val req = router.routeRequirements(IntentType.MEMORY_QUERY, "¿Qué recuerdas?")
        assertTrue(req.needsMemory)
        assertTrue(req.levels.contains(ContextLevel.LEVEL_3_LONG_TERM_MEMORY))
    }

    @Test
    fun testContextRouterObsidianQuery() {
        val req = router.routeRequirements(IntentType.OBSIDIAN_SEARCH, "Busca en mis notas")
        assertTrue(req.needsKnowledge)
        assertTrue(req.levels.contains(ContextLevel.LEVEL_4_OBSIDIAN_VAULT))
    }

    @Test
    fun testContextBudgetWithinLimit() {
        val items = listOf(
            ContextItem("1", ContextLevel.LEVEL_0_IDENTITY, ContextPriority.CRITICAL, "System rules", "system", 50),
            ContextItem("2", ContextLevel.LEVEL_1_SESSION, ContextPriority.HIGH, "User query", "user", 50)
        )
        val optimized = budget.optimize(items)
        assertEquals(2, optimized.size)
    }

    @Test
    fun testContextBudgetExceedsLimit() {
        val items = listOf(
            ContextItem("1", ContextLevel.LEVEL_0_IDENTITY, ContextPriority.CRITICAL, "System rules", "system", 50),
            ContextItem("2", ContextLevel.LEVEL_5_HISTORY, ContextPriority.LOW, "Very long old history note...", "history", 400)
        )
        val optimized = budget.optimize(items)
        // Critical is kept, low priority exceeding budget is dropped
        assertTrue(optimized.any { it.priority == ContextPriority.CRITICAL })
        assertFalse(optimized.any { it.id == "2" })
    }
}
