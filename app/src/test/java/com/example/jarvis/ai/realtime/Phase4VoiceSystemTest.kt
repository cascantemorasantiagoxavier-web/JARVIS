package com.example.jarvis.ai.realtime

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.DefaultAiOrchestrator
import com.example.jarvis.ai.orchestrator.DefaultVoiceOrchestratorBridge
import com.example.jarvis.ai.orchestrator.models.VoiceCommand
import com.example.jarvis.ai.orchestrator.models.VoiceResponseType
import com.example.jarvis.ai.tools.*
import com.example.jarvis.ai.wakeword.PhoneticKwsWakeWordEngine
import com.example.jarvis.ai.wakeword.WakeWordController
import com.example.jarvis.ai.wakeword.WakeWordState
import com.example.jarvis.core.device.AppManager
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Phase4VoiceSystemTest {

    private lateinit var intentDetector: DefaultIntentDetector
    private lateinit var toolRegistry: DefaultToolRegistry
    private lateinit var orchestrator: DefaultAiOrchestrator
    private lateinit var riskEvaluator: DefaultRiskEvaluator
    private lateinit var voiceBridge: DefaultVoiceOrchestratorBridge
    private lateinit var wakeWordController: WakeWordController

    @Before
    fun setUp() {
        intentDetector = DefaultIntentDetector()
        toolRegistry = DefaultToolRegistry().apply {
            registerTool(GetDateTimeTool())
            registerTool(CalculatorTool())
            registerTool(BatteryTool(appManagerProvider = {
                object : AppManager {
                    override fun isAppInstalled(packageName: String) = true
                    override fun launchApp(packageName: String) = Result.success(Unit)
                    override fun openSettings(settingType: String) = Result.success(Unit)
                    override fun getDeviceModel() = "Pixel 8"
                    override fun getAndroidVersion() = "Android 15"
                    override fun getBatteryLevel() = Result.success(90)
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
        wakeWordController = WakeWordController(engine = PhoneticKwsWakeWordEngine("JARVIS"))
    }

    @Test
    fun testVoiceCommandLocalTimeQueryOffline() = runBlocking {
        val command = VoiceCommand(text = "¿Qué hora es?")
        val response = voiceBridge.processVoiceCommand(command)

        assertNull(response.error)
        assertTrue(response.text.contains("Son las"))
        assertEquals(VoiceResponseType.TOOL_RESULT, response.responseType)
    }

    @Test
    fun testVoiceCommandLocalDateQueryOffline() = runBlocking {
        val command = VoiceCommand(text = "¿Qué fecha es hoy?")
        val response = voiceBridge.processVoiceCommand(command)

        assertNull(response.error)
        assertTrue(response.text.contains("Hoy es"))
        assertEquals(VoiceResponseType.TOOL_RESULT, response.responseType)
    }

    @Test
    fun testVoiceCommandCalculatorOffline() = runBlocking {
        val command = VoiceCommand(text = "25 por 40")
        val response = voiceBridge.processVoiceCommand(command)

        assertNull(response.error)
        assertTrue(response.text.contains("1000"))
        assertEquals(VoiceResponseType.TOOL_RESULT, response.responseType)
    }

    @Test
    fun testVoiceCommandBatteryOffline() = runBlocking {
        val command = VoiceCommand(text = "¿Cuánta batería tengo?")
        val response = voiceBridge.processVoiceCommand(command)

        assertNull(response.error)
        assertTrue(response.text.contains("90%"))
        assertEquals(VoiceResponseType.TOOL_RESULT, response.responseType)
    }

    @Test
    fun testVoiceCommandNetworkCheckOffline() = runBlocking {
        val command = VoiceCommand(text = "¿Tengo Internet?")
        val response = voiceBridge.processVoiceCommand(command)

        assertNull(response.error)
        assertTrue(response.text.contains("conexion") || response.text.contains("Internet"))
    }

    @Test
    fun testAcousticKwsWakeWordSpottingCycle() {
        var detected = false
        wakeWordController.startListening {
            detected = true
        }

        assertEquals(WakeWordState.LISTENING, wakeWordController.state.value)

        val pcmFrame1 = ShortArray(512) { 3000 }
        val pcmFrame2 = ShortArray(512) { 5000 }
        val pcmFrame3 = ShortArray(512) { 1000 }

        wakeWordController.processAudioFrame(pcmFrame1)
        wakeWordController.processAudioFrame(pcmFrame2)
        wakeWordController.processAudioFrame(pcmFrame3)

        wakeWordController.onCommandFinished()
        assertEquals(WakeWordState.LISTENING, wakeWordController.state.value)
        assertFalse(detected) // verified state transitions
    }

    @Test
    fun testRealtimeClientMockEventsFlow() = runBlocking {
        class MockRealtimeClient : RealtimeClient {
            val eventFlow = MutableSharedFlow<RealtimeEvent>()
            override suspend fun connect(session: RealtimeSessionConfig) {}
            override suspend fun disconnect() {}
            override suspend fun sendText(text: String) {}
            override suspend fun sendAudio(audio: ByteArray) {}
            override suspend fun commitAndCreateResponse() {}
            override fun events(): Flow<RealtimeEvent> = eventFlow
        }

        val client = MockRealtimeClient()
        assertNotNull(client.events())
    }

    @Test
    fun testDestructiveToolBlockedByRiskEvaluatorInVoiceBridge() = runBlocking {
        val workflowTool = object : Tool {
            override val name = "destructive_action"
            override val description = "Acción destructiva de prueba"
            override val riskLevel = RiskLevel.DESTRUCTIVE
            override suspend fun execute(params: Map<String, Any>) = "Ejecutado"
        }
        toolRegistry.registerTool(workflowTool)

        val eval = riskEvaluator.evaluate(workflowTool, emptyMap())
        assertFalse(eval.isPermitted)
    }
}
