package com.example.jarvis.core.di

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JarvisBrainCoreMasterTest {

    @Test
    fun testJarvisBrainSystemDiagnosticCommand() = runBlocking {
        val request = JarvisRequest(text = "JARVIS, haz un diagnóstico completo", source = RequestSource.CHAT)
        val response = JarvisBrain.processRequest(request)

        assertTrue(response.success)
        assertEquals("system_diagnostic", response.action)
        assertTrue(response.text.contains("JARVIS SYSTEM DIAGNOSTIC REPORT"))
        assertTrue(response.text.contains("Tools Registered"))
    }

    @Test
    fun testJarvisBrainFastPathDateTime() = runBlocking {
        val request = JarvisRequest(text = "JARVIS, ¿qué hora es?", source = RequestSource.VOICE)
        val response = JarvisBrain.processRequest(request)

        assertTrue(response.success)
        assertTrue(response.text.isNotBlank())
        assertTrue("Execution time should be measured in ms", response.executionTimeMs >= 0)
    }

    @Test
    fun testJarvisBrainIdentityMemoryIntegration() = runBlocking {
        val saveReq = JarvisRequest(text = "Me llamo Santiago.", source = RequestSource.CHAT)
        val saveRes = JarvisBrain.processRequest(saveReq)
        System.err.println("DEBUG_SAVE_RES: ${saveRes.text} | success=${saveRes.success}")
        assertTrue(saveRes.success)

        val recallReq = JarvisRequest(text = "¿Cómo me llamo?", source = RequestSource.VOICE)
        val recallRes = JarvisBrain.processRequest(recallReq)
        System.err.println("DEBUG_RECALL_RES: ${recallRes.text} | success=${recallRes.success}")
        assertTrue(recallRes.success)
        assertTrue(recallRes.text.contains("Santiago"))
    }
}
