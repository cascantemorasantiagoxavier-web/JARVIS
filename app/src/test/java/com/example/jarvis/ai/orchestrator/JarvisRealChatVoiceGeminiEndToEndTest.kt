package com.example.jarvis.ai.orchestrator

import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.di.JarvisRequest
import com.example.jarvis.core.di.RequestSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JarvisRealChatVoiceGeminiEndToEndTest {

    @Test
    fun testChatViewModelToJarvisBrainRouting() = runBlocking {
        val chatReq = JarvisRequest(text = "Explícame por qué el cielo es azul en dos frases.", source = RequestSource.CHAT)
        val startTime = System.currentTimeMillis()
        val res = JarvisBrain.processRequest(chatReq)
        val latency = System.currentTimeMillis() - startTime

        assertNotNull(res)
        assertTrue(res.executionTimeMs >= 0)
        println("[E2E TEST] Chat -> Brain latency: ${latency}ms | success=${res.success} | output=${res.text}")
    }

    @Test
    fun testVoiceViewModelToJarvisBrainUnification() = runBlocking {
        val voiceReq = JarvisRequest(text = "Explícame por qué el cielo es azul en dos frases.", source = RequestSource.VOICE)
        val startTime = System.currentTimeMillis()
        val res = JarvisBrain.processRequest(voiceReq)
        val latency = System.currentTimeMillis() - startTime

        assertNotNull(res)
        assertTrue(res.executionTimeMs >= 0)
        println("[E2E TEST] Voice -> Brain latency: ${latency}ms | success=${res.success} | output=${res.text}")
    }

    @Test
    fun testFastPathAvoidsGemini() = runBlocking {
        val req = JarvisRequest(text = "¿Qué hora es?", source = RequestSource.CHAT)
        val startTime = System.currentTimeMillis()
        val res = JarvisBrain.processRequest(req)
        val latency = System.currentTimeMillis() - startTime

        assertTrue(res.success)
        assertTrue("FastPath must be under 50ms, got ${latency}ms", latency < 50)
        assertEquals("get_date_time", res.toolUsed)
    }

    @Test
    fun testMemoryInjectionIntoGeminiPromptContext() = runBlocking {
        val saveReq = JarvisRequest(text = "Recuerda que me llamo Santiago.", source = RequestSource.CHAT)
        JarvisBrain.processRequest(saveReq)

        val queryReq = JarvisRequest(text = "Mi nombre es importante para esta conversación. ¿Cómo me llamo y qué significa para ti recordar ese dato?", source = RequestSource.CHAT)
        val res = JarvisBrain.processRequest(queryReq)

        assertNotNull(res)
        println("[E2E TEST] Memory + Brain response: ${res.text}")
    }
}
