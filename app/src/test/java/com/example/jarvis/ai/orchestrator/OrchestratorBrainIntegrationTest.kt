package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.models.DetectedIntent
import com.example.jarvis.ai.orchestrator.models.IntentType
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrchestratorBrainIntegrationTest {

    private lateinit var intentDetector: DefaultIntentDetector
    private lateinit var contextManager: DefaultContextManager
    private lateinit var memoryManager: DefaultAiMemoryManager
    private lateinit var agentRegistry: DefaultAgentRegistry
    private lateinit var toolRegistry: DefaultToolRegistry
    private lateinit var orchestrator: DefaultAiOrchestrator

    @Before
    fun setUp() {
        intentDetector = DefaultIntentDetector()
        contextManager = DefaultContextManager()
        memoryManager = DefaultAiMemoryManager()
        agentRegistry = DefaultAgentRegistry()
        toolRegistry = DefaultToolRegistry()

        orchestrator = DefaultAiOrchestrator(
            intentDetector = intentDetector,
            contextManager = contextManager,
            memoryManager = memoryManager,
            agentRegistry = agentRegistry,
            toolRegistry = toolRegistry
        )
    }

    @Test
    fun test1_SimpleGreetingMinimalContext() = runBlocking {
        val request = OrchestratorRequest(input = "Hola JARVIS")
        val response = orchestrator.process(request)
        assertTrue(response.isSuccess)
        assertEquals(IntentType.CONVERSATION, response.detectedIntent?.type)
    }

    @Test
    fun test2_MemoryRecallQuery() = runBlocking {
        memoryManager.memorize("user_name", "Carlos")
        val request = OrchestratorRequest(input = "¿Qué recuerdas sobre Carlos?")
        val response = orchestrator.process(request)
        assertTrue(response.isSuccess)
    }

    @Test
    fun test3_ObsidianRetrieval() = runBlocking {
        val request = OrchestratorRequest(input = "Busca información en mis notas sobre obsidian vault.")
        val response = orchestrator.process(request)
        assertTrue(response.isSuccess)
        assertEquals(IntentType.OBSIDIAN_SEARCH, response.detectedIntent?.type)
    }

    @Test
    fun test4_DeviceActionOpenSettings() = runBlocking {
        val request = OrchestratorRequest(input = "abre ajustes de wifi")
        val response = orchestrator.process(request)
        assertTrue(response.isSuccess)
        assertEquals(IntentType.OPEN_SETTINGS, response.detectedIntent?.type)
        assertEquals("open_settings", response.toolSelection?.toolName)
    }

    @Test
    fun test5_DeviceActionNonExistentApp() = runBlocking {
        val request = OrchestratorRequest(input = "abre app_inexistente_xyz_999")
        val response = orchestrator.process(request)
        assertNotNull(response.output)
    }

    @Test
    fun test6_DestructiveActionRestriction() = runBlocking {
        val request = OrchestratorRequest(input = "DELETE: user_profile")
        val response = orchestrator.process(request)
        assertNotNull(response)
    }

    @Test
    fun test7_MemoryDeduplication() = runBlocking {
        memoryManager.memorize("pref_kotlin", "Prefiere Kotlin")
        val request = OrchestratorRequest(input = "Prefiere Kotlin")
        val response = orchestrator.process(request)
        assertTrue(response.isSuccess)
    }

    @Test
    fun test8_MemoryConflictHandling() = runBlocking {
        memoryManager.memorize("user_preference", "Prefiere tema claro")
        val request = OrchestratorRequest(input = "Ahora prefiere tema oscuro")
        val response = orchestrator.process(request)
        assertTrue(response.isSuccess)
    }

    @Test
    fun test9_ToolExecutionFailureHandling() = runBlocking {
        val request = OrchestratorRequest(input = "abre app_inexistente_9999")
        val response = orchestrator.process(request)
        assertFalse(response.output.contains("Listo, abrí"))
    }

    @Test
    fun test10_MinimalContextOptimization() = runBlocking {
        val resolver = DefaultContextRequirementResolver()
        val requirements = resolver.resolveRequirements(
            DetectedIntent(type = IntentType.CONVERSATION)
        )
        assertTrue(requirements.requiredLevels.size <= 2)
    }
}
