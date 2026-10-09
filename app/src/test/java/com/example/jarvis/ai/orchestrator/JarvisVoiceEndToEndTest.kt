package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.models.*
import com.example.jarvis.ai.realtime.*
import com.example.jarvis.ai.tools.DefaultRiskEvaluator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JarvisVoiceEndToEndTest {

    private class MockRealtimeClient : RealtimeClient {
        val eventFlow = MutableSharedFlow<RealtimeEvent>()
        val sentTexts = mutableListOf<String>()

        override suspend fun connect(session: RealtimeSessionConfig) {}
        override suspend fun disconnect() {}
        override suspend fun sendText(text: String) { sentTexts.add(text) }
        override suspend fun sendAudio(audio: ByteArray) {}
        override suspend fun commitAndCreateResponse() {}
        override fun events(): Flow<RealtimeEvent> = eventFlow
    }

    private lateinit var mockRealtime: MockRealtimeClient
    private lateinit var intentDetector: DefaultIntentDetector
    private lateinit var contextManager: DefaultContextManager
    private lateinit var memoryManager: DefaultAiMemoryManager
    private lateinit var agentRegistry: DefaultAgentRegistry
    private lateinit var toolRegistry: DefaultToolRegistry
    private lateinit var orchestrator: DefaultAiOrchestrator
    private lateinit var riskEvaluator: DefaultRiskEvaluator
    private lateinit var bridge: DefaultVoiceOrchestratorBridge
    private lateinit var service: JarvisVoiceOrchestratorService

    @Before
    fun setUp() {
        mockRealtime = MockRealtimeClient()
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

        service = JarvisVoiceOrchestratorService(
            realtimeClient = mockRealtime,
            bridge = bridge
        )
    }

    @Test
    fun testEndToEndOpenWifiSettings() = runBlocking {
        val response = service.handleTextCommand("Abre los ajustes de Wi-Fi")
        assertTrue(response.error == null || response.responseType == VoiceResponseType.ACTION || response.responseType == VoiceResponseType.TOOL_RESULT)
        assertNotNull(response.text)
    }

    @Test
    fun testEndToEndDeviceInfoQuery() = runBlocking {
        val response = service.handleTextCommand("¿Qué dispositivo estoy usando?")
        assertNotNull(response.text)
    }

    @Test
    fun testEndToEndSimpleConversation() = runBlocking {
        val response = service.handleTextCommand("Hola JARVIS")
        assertEquals(VoiceResponseType.CONVERSATION, response.responseType)
    }

    @Test
    fun testEndToEndToolFailureHandling() = runBlocking {
        val response = service.handleTextCommand("abre app_inexistente_xyz_9999")
        assertFalse(response.text.contains("Listo, abrí"))
    }
}
