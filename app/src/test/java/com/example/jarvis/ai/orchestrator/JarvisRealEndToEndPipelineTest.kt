package com.example.jarvis.ai.orchestrator

import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.di.JarvisRequest
import com.example.jarvis.core.di.RequestSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JarvisRealEndToEndPipelineTest {

    @Test
    fun testFastPathExecutionWithoutGemini() = runBlocking {
        val req = JarvisRequest(text = "¿Qué hora es?", source = RequestSource.CHAT)
        val startTime = System.currentTimeMillis()
        val res = JarvisBrain.processRequest(req)
        val latency = System.currentTimeMillis() - startTime

        assertTrue(res.success)
        assertTrue("FastPath should be under 50ms, got ${latency}ms", latency < 50)
        assertNotNull(res.text)
    }

    @Test
    fun testVoiceAndChatUnificationSameBrain() = runBlocking {
        val chatReq = JarvisRequest(text = "15 + 27", source = RequestSource.CHAT)
        val voiceReq = JarvisRequest(text = "15 + 27", source = RequestSource.VOICE)

        val chatRes = JarvisBrain.processRequest(chatReq)
        val voiceRes = JarvisBrain.processRequest(voiceReq)

        assertTrue(chatRes.success)
        assertTrue(voiceRes.success)
        assertEquals(chatRes.text, voiceRes.text)
    }

    @Test
    fun testMemoryRealPersistenceAndContextRecall() = runBlocking {
        val saveReq = JarvisRequest(text = "Recuerda que me llamo Santiago.", source = RequestSource.CHAT)
        val saveRes = JarvisBrain.processRequest(saveReq)
        assertTrue(saveRes.success)

        val recallReq = JarvisRequest(text = "¿Cómo me llamo?", source = RequestSource.CHAT)
        val recallRes = JarvisBrain.processRequest(recallReq)
        assertTrue(recallRes.success)
        assertTrue(recallRes.text.contains("Santiago", ignoreCase = true))
    }
}
