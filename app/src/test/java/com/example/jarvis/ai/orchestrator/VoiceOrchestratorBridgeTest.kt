package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.models.*
import com.example.jarvis.ai.tools.DefaultRiskEvaluator
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceOrchestratorBridgeTest {

    private lateinit var intentDetector: DefaultIntentDetector
    private lateinit var contextManager: DefaultContextManager
    private lateinit var memoryManager: DefaultAiMemoryManager
    private lateinit var agentRegistry: DefaultAgentRegistry
    private lateinit var toolRegistry: DefaultToolRegistry
    private lateinit var orchestrator: DefaultAiOrchestrator
    private lateinit var riskEvaluator: DefaultRiskEvaluator
    private lateinit var bridge: DefaultVoiceOrchestratorBridge

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

        riskEvaluator = DefaultRiskEvaluator()
        bridge = DefaultVoiceOrchestratorBridge(
            orchestrator = orchestrator,
            riskEvaluator = riskEvaluator,
            toolRegistry = toolRegistry
        )
    }

    @Test
    fun test1_VoiceCommandToUserRequestConversion() {
        val voiceCommand = VoiceCommand(text = "Hola JARVIS", sessionId = "session_123")
        val request = voiceCommand.toOrchestratorRequest()
        assertEquals("Hola JARVIS", request.input)
        assertEquals("session_123", request.sessionId)
        assertEquals("VOICE", request.metadata["origin"])
    }

    @Test
    fun test2_UserRequestToOrchestratorExecution() = runBlocking {
        val command = VoiceCommand(text = "Hola JARVIS")
        val response = bridge.processVoiceCommand(command)
        assertNotNull(response)
        assertEquals(VoiceResponseType.CONVERSATION, response.responseType)
    }

    @Test
    fun test3_OrchestratorToolSelectionDelegation() = runBlocking {
        val command = VoiceCommand(text = "abre ajustes de wifi")
        val response = bridge.processVoiceCommand(command)
        assertNotNull(response)
        assertEquals(VoiceResponseType.ACTION, response.responseType)
    }

    @Test
    fun test4_ToolResultToVoiceResponseMapping() = runBlocking {
        val orchestratorResponse = OrchestratorResponse(
            requestId = "req_1",
            output = "Abriendo ajustes de wifi...",
            isSuccess = true,
            toolSelection = ToolSelection(toolName = "open_settings")
        )
        val voiceResponse = VoiceResponse.fromOrchestratorResponse(orchestratorResponse)
        assertEquals(VoiceResponseType.TOOL_RESULT, voiceResponse.responseType)
        assertEquals("Abriendo ajustes de wifi...", voiceResponse.toolResult)
    }

    @Test
    fun test5_ConversationHandling() = runBlocking {
        val command = VoiceCommand(text = "Hola")
        val response = bridge.processVoiceCommand(command)
        assertEquals(VoiceResponseType.CONVERSATION, response.responseType)
    }

    @Test
    fun test6_QuestionHandling() = runBlocking {
        val command = VoiceCommand(text = "¿Qué hora es?")
        val response = bridge.processVoiceCommand(command)
        assertEquals(VoiceResponseType.QUESTION, response.responseType)
    }

    @Test
    fun test7_DeviceActionHandling() = runBlocking {
        val command = VoiceCommand(text = "batería")
        val response = bridge.processVoiceCommand(command)
        assertNotNull(response)
    }

    @Test
    fun test8_ToolFailureHandlingNoFakeSuccess() = runBlocking {
        val command = VoiceCommand(text = "abre app_inexistente_9999")
        val response = bridge.processVoiceCommand(command)
        assertFalse(response.text.contains("Listo, abrí"))
    }

    @Test
    fun test9_PermissionDeniedHandling() = runBlocking {
        val destructiveTool = object : Tool {
            override val name = "destructive_tool"
            override val description = "Test destructive tool"
            override val riskLevel = RiskLevel.DESTRUCTIVE
            override suspend fun execute(params: Map<String, Any>): String = "Executed"
        }
        toolRegistry.registerTool(destructiveTool)

        val evaluation = riskEvaluator.evaluate(destructiveTool, emptyMap())
        assertFalse(evaluation.isPermitted)
    }

    @Test
    fun test10_DestructiveActionRequiringConfirmation() = runBlocking {
        val destructiveTool = object : Tool {
            override val name = "delete_all"
            override val description = "Delete all data"
            override val riskLevel = RiskLevel.DESTRUCTIVE
            override suspend fun execute(params: Map<String, Any>): String = "Deleted"
        }
        val evaluation = riskEvaluator.evaluate(destructiveTool, emptyMap())
        assertTrue(evaluation.requiresUserConfirmation)
        assertFalse(evaluation.isPermitted)
    }
}
