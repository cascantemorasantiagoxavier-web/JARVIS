package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.ContextLevel
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrchestratorPipelineTest {

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
    fun testEmptyInputHandledGracefully() = runBlocking {
        val response = orchestrator.process(OrchestratorRequest(input = ""))
        assertFalse(response.isSuccess)
        assertEquals("Empty input received", response.output)
        assertNotNull(response.error)
    }

    @Test
    fun testCodingIntentPipelineFlow() = runBlocking {
        val request = OrchestratorRequest(input = "Write Kotlin code for a repository")
        val response = orchestrator.process(request)

        assertTrue(response.isSuccess)
        assertNotNull(response.detectedIntent)
        assertEquals(IntentType.CODING, response.detectedIntent?.type)
        assertEquals("coding_agent", response.agentSelection?.agentId)
        assertNotNull(response.validationResult)
        assertTrue(response.validationResult!!.isValid)
        assertEquals(MemoryAction.MEMORIZE, response.memoryDecision?.action)
    }

    @Test
    fun testIntentTypeFromStringParsing() {
        assertEquals(IntentType.CODING, IntentType.fromString("CODING"))
        assertEquals(IntentType.OBSIDIAN, IntentType.fromString("obsidian"))
        assertEquals(IntentType.UNKNOWN, IntentType.fromString("invalid_intent_xyz"))
    }

    @Test
    fun testBackwardCompatibleCoordinateMethod() = runBlocking {
        val result = orchestrator.coordinate("Search memory for user preference")
        assertTrue(result.contains("Processed intent") || result.isNotBlank())
    }

    @Test
    fun testDefaultContextRequirementResolver() = runBlocking {
        val resolver = DefaultContextRequirementResolver()
        val requirements = resolver.resolveRequirements(
            DetectedIntent(type = IntentType.OBSIDIAN)
        )
        assertTrue(requirements.requiredLevels.contains(ContextLevel.LEVEL_4_OBSIDIAN_VAULT))
    }

    @Test
    fun testDefaultResultValidator() = runBlocking {
        val validator = DefaultResultValidator()
        val validResult = validator.validate("test input", "valid output")
        assertTrue(validResult.isValid)

        val invalidResult = validator.validate("test input", "")
        assertFalse(invalidResult.isValid)
    }

    @Test
    fun testDefaultMemoryDecider() = runBlocking {
        val decider = DefaultMemoryDecider()
        val decision = decider.decide("key1", "val1", ValidationResult(isValid = true))
        assertEquals(MemoryAction.MEMORIZE, decision.action)
        assertEquals("key1", decision.key)
        assertEquals("val1", decision.value)
    }
}
