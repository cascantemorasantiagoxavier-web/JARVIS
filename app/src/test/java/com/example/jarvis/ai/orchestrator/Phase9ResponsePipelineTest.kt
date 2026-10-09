package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.models.*
import com.example.jarvis.ai.tools.*
import com.example.jarvis.core.device.AppManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Phase9ResponsePipelineTest {

    private lateinit var intentDetector: DefaultIntentDetector
    private lateinit var orchestrator: DefaultAiOrchestrator
    private lateinit var riskEvaluator: DefaultRiskEvaluator
    private lateinit var voiceBridge: DefaultVoiceOrchestratorBridge

    @Before
    fun setUp() {
        intentDetector = DefaultIntentDetector()
        val toolRegistry = DefaultToolRegistry().apply {
            registerTool(GetDateTimeTool())
            registerTool(CalculatorTool())
            registerTool(BatteryTool(appManagerProvider = {
                object : AppManager {
                    override fun isAppInstalled(packageName: String) = true
                    override fun launchApp(packageName: String) = Result.success(Unit)
                    override fun openSettings(settingType: String) = Result.success(Unit)
                    override fun getDeviceModel() = "Pixel 8"
                    override fun getAndroidVersion() = "Android 15"
                    override fun getBatteryLevel() = Result.success(88)
                }
            }))
            registerTool(CheckNetworkTool())
        }

        orchestrator = DefaultAiOrchestrator(
            intentDetector = intentDetector,
            contextManager = DefaultContextManager(),
            memoryManager = DefaultAiMemoryManager(),
            agentRegistry = DefaultAgentRegistry(),
            toolRegistry = toolRegistry
        )

        riskEvaluator = DefaultRiskEvaluator()
        voiceBridge = DefaultVoiceOrchestratorBridge(orchestrator, riskEvaluator, toolRegistry)
    }

    @Test
    fun test1_GreetingReturnsNaturalResponseNotInternalLeak() = runBlocking {
        val request = OrchestratorRequest(input = "Hola")
        val response = orchestrator.process(request)
        val userFacing = UserResponseMapper.map(response)

        assertFalse(userFacing.text.contains("Orchestrator response for", ignoreCase = true))
        assertFalse(userFacing.text.contains("OrchestratorResult", ignoreCase = true))
        assertTrue(userFacing.text.contains("Hola") || userFacing.text.contains("ayudar"))
    }

    @Test
    fun test2_TimeQueryReturnsNaturalResponse() = runBlocking {
        val request = OrchestratorRequest(input = "¿Qué hora es?")
        val response = orchestrator.process(request)
        val userFacing = UserResponseMapper.map(response)

        assertFalse(userFacing.text.contains("ToolResult", ignoreCase = true))
        assertTrue(userFacing.text.contains("Son las"))
    }

    @Test
    fun test3_CalculatorReturnsCleanResult() = runBlocking {
        val request = OrchestratorRequest(input = "25 por 8")
        val response = orchestrator.process(request)
        val userFacing = UserResponseMapper.map(response)

        assertFalse(userFacing.text.contains("CalculatorTool", ignoreCase = true))
        assertTrue(userFacing.text.contains("200"))
    }

    @Test
    fun test4_BatteryReturnsNaturalResponse() = runBlocking {
        val request = OrchestratorRequest(input = "¿Cuánta batería tengo?")
        val response = orchestrator.process(request)
        val userFacing = UserResponseMapper.map(response)

        assertFalse(userFacing.text.contains("BatteryTool", ignoreCase = true))
        assertTrue(userFacing.text.contains("88%"))
    }

    @Test
    fun test5_NetworkReturnsNaturalResponse() = runBlocking {
        val request = OrchestratorRequest(input = "¿Tengo Internet?")
        val response = orchestrator.process(request)
        val userFacing = UserResponseMapper.map(response)

        assertFalse(userFacing.text.contains("check_network", ignoreCase = true))
        assertTrue(userFacing.text.contains("Internet") || userFacing.text.contains("conexión"))
    }

    @Test
    fun test6_VoiceBridgeReturnsCleanUserFacingText() = runBlocking {
        val command = VoiceCommand(text = "¿Qué hora es?")
        val voiceResp = voiceBridge.processVoiceCommand(command)

        assertFalse(voiceResp.text.contains("Orchestrator", ignoreCase = true))
        assertFalse(voiceResp.text.contains("get_date_time", ignoreCase = true))
        assertTrue(voiceResp.text.contains("Son las"))
    }

    @Test
    fun test7_ErrorHandlingFriendlyMessage() = runBlocking {
        val request = OrchestratorRequest(input = "   ")
        val response = orchestrator.process(request)
        val userFacing = UserResponseMapper.map(response)

        assertFalse(userFacing.text.contains("NullPointerException", ignoreCase = true))
        assertFalse(userFacing.text.contains("Stacktrace", ignoreCase = true))
    }
}
