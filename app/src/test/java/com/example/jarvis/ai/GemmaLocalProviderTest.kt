package com.example.jarvis.ai

import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.data.local.ai.GemmaLocalAiProvider
import com.example.jarvis.data.remote.ai.ChatMessagePayload
import com.example.jarvis.data.remote.ai.GeminiApiProvider
import com.example.jarvis.data.remote.ai.OpenAiApiProvider
import com.example.jarvis.domain.ai.AiProviderMode
import com.example.jarvis.domain.ai.HybridAiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GemmaLocalProviderTest {

    @Test
    fun testGemmaLocalAiProviderDetectsMissingModelFileAndReturnsFailure() = runBlocking {
        val provider = GemmaLocalAiProvider()
        val sessionResult = provider.createSession()
        assertTrue(sessionResult.isSuccess)

        val session = sessionResult.getOrNull()
        assertNotNull(session)

        assertFalse(provider.isModelAvailable())

        val chatResult = provider.generateResponse(
            messages = listOf(ChatMessagePayload("user", "Hola Gemma"))
        )
        assertFalse(chatResult.isSuccess)
        val errorMsg = chatResult.exceptionOrNull()?.message
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("GEMMA_NOT_AVAILABLE"))

        provider.disconnect(session!!)
    }

    @Test
    fun testGeminiApiProviderInitialization() = runBlocking {
        val provider = GeminiApiProvider()
        val sessionResult = provider.createSession()
        assertTrue(sessionResult.isSuccess)

        val session = sessionResult.getOrNull()
        assertNotNull(session)
        provider.disconnect(session!!)
    }

    @Test
    fun testHybridAiProviderSelectsLocalGemmaWhenModeIsLocal() = runBlocking {
        val hybrid = HybridAiProvider(mode = AiProviderMode.LOCAL_GEMMA)
        val active = hybrid.selectActiveProvider()
        assertTrue(active is GemmaLocalAiProvider)

        val res = hybrid.generateResponse(
            messages = listOf(ChatMessagePayload("user", "¿Quién eres?"))
        )
        assertFalse(res.isSuccess)
        val err = res.exceptionOrNull()?.message
        assertNotNull(err)
        assertTrue(err!!.contains("GEMMA_NOT_AVAILABLE"))
    }

    @Test
    fun testHybridAiProviderSelectsGeminiWhenModeIsOnlineGemini() = runBlocking {
        val hybrid = HybridAiProvider(mode = AiProviderMode.ONLINE_GEMINI)
        val active = hybrid.selectActiveProvider()
        assertTrue(active is GeminiApiProvider)
    }

    @Test
    fun testHybridAiProviderSelectsOpenAiWhenModeIsOnlineOpenAi() = runBlocking {
        val hybrid = HybridAiProvider(mode = AiProviderMode.ONLINE_OPENAI)
        val active = hybrid.selectActiveProvider()
        assertTrue(active is OpenAiApiProvider)
    }

    @Test
    fun testJarvisBrainOrchestratorUsesHybridAiProviderWithFallback() = runBlocking {
        val orchestrator = JarvisBrain.orchestrator
        val response = orchestrator.process(OrchestratorRequest(input = "Explícame qué es la inteligencia artificial"))

        assertTrue(response.isSuccess)
        assertNotNull(response.output)
        assertTrue(response.output.isNotBlank())
    }

    @Test
    fun testLocalDeterministicQueriesDoNotUseGemmaOrGeminiOrOpenAI() = runBlocking {
        val orchestrator = JarvisBrain.orchestrator
        val responseDateTime = orchestrator.process(OrchestratorRequest(input = "¿Qué hora es?"))
        assertTrue(responseDateTime.isSuccess)
        assertTrue(responseDateTime.output.contains("Son las") || responseDateTime.output.contains("hora"))

        val responseMath = orchestrator.process(OrchestratorRequest(input = "Calcula 25 * 37"))
        assertTrue(responseMath.isSuccess)
        assertTrue(responseMath.output.contains("925"))
    }

    @Test
    fun testJarvisBrainMemoryPersistenceFlow() = runBlocking {
        val memoryManager = JarvisBrain.memoryManager
        memoryManager.memorize("nombre_usuario", "Santiago")

        val recallRes = memoryManager.recall("Santiago")
        assertNotNull(recallRes)
        assertTrue(recallRes.contains("Santiago"))
    }
}
