package com.example.jarvis.ai.routing

import com.example.jarvis.ai.orchestrator.models.IntentType
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModelRouterTest {

    private lateinit var router: DefaultModelRouter

    @Before
    fun setUp() {
        router = DefaultModelRouter()
    }

    @Test
    fun test1_ConversationRouting() {
        val request = OrchestratorRequest(input = "Hola JARVIS")
        val context = RoutingContext(intentType = IntentType.CONVERSATION, isVoice = true)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.VOICE_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test2_SimpleClassificationRouting() {
        val request = OrchestratorRequest(input = "Clasifica este texto")
        val context = RoutingContext(intentType = IntentType.GENERAL)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.FAST_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test3_MemoryClassificationRouting() {
        val request = OrchestratorRequest(input = "Guarda esta memoria")
        val context = RoutingContext(intentType = IntentType.MEMORY_WRITE)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.FAST_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test4_SimpleProductivityTaskRouting() {
        val request = OrchestratorRequest(input = "Crea una tarea")
        val context = RoutingContext(intentType = IntentType.TASK_CREATE)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.FAST_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test5_ComplexPlanningRouting() {
        val request = OrchestratorRequest(input = "Analiza mis proyectos y crea un plan complejo multi-step")
        val context = RoutingContext(intentType = IntentType.PLANNING, complexity = ComplexityLevel.HIGH, requiresReasoning = true)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.REASONING_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test6_ResearchRouting() {
        val request = OrchestratorRequest(input = "Investiga sobre inteligencia artificial avanzada")
        val context = RoutingContext(intentType = IntentType.RESEARCH, requiresReasoning = true)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.REASONING_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test7_CodingRouting() {
        val request = OrchestratorRequest(input = "Escribe código Kotlin para un repositorio")
        val context = RoutingContext(intentType = IntentType.CODING, requiresReasoning = true)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.REASONING_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test8_DeviceActionRouting() {
        val request = OrchestratorRequest(input = "Abre YouTube")
        val context = RoutingContext(intentType = IntentType.DEVICE, requiresTools = true)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.FAST_MODEL.id, selection.selectedModel.id)
        assertTrue(selection.requiresTools)
    }

    @Test
    fun test9_VoiceRequestRouting() {
        val request = OrchestratorRequest(input = "Hola", metadata = mapOf("origin" to "VOICE"))
        val context = RoutingContext(intentType = IntentType.GENERAL)
        val selection = router.selectModel(request, context)
        assertEquals(ModelRegistry.VOICE_MODEL.id, selection.selectedModel.id)
    }

    @Test
    fun test10_FallbackConfiguration() {
        val request = OrchestratorRequest(input = "Test fallback")
        val context = RoutingContext(intentType = IntentType.GENERAL)
        val selection = router.selectModel(request, context)
        assertNotNull(selection.fallbackModel)
        assertEquals(ModelRegistry.REASONING_MODEL.id, selection.fallbackModel?.id)
    }
}
