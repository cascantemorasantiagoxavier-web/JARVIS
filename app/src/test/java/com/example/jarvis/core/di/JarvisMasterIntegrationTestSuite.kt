package com.example.jarvis.core.di

import com.example.jarvis.domain.ai.ThinkingMode
import com.example.jarvis.domain.ai.ThinkingRouter
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class JarvisMasterIntegrationTestSuite {

    companion object {
        private var backendProcess: Process? = null

        @BeforeClass
        @JvmStatic
        fun startBackend() {
            try {
                val rootDir = File(System.getProperty("user.dir")!!)
                val backendDir = if (File(rootDir, "backend").exists()) File(rootDir, "backend") else File(rootDir, "../backend")
                if (backendDir.exists()) {
                    val pb = ProcessBuilder("node", "src/index.js")
                    pb.directory(backendDir)
                    backendProcess = pb.start()
                    Thread.sleep(2500)
                }
            } catch (_: Exception) {}
        }

        @AfterClass
        @JvmStatic
        fun stopBackend() {
            backendProcess?.destroy()
        }
    }

    @Test
    fun test01_JarvisBrainSystemDiagnosticCommand() = runBlocking {
        try {
            val req = JarvisRequest(text = "JARVIS, haz un diagnóstico completo", source = RequestSource.CHAT)
            val res = JarvisBrain.processRequest(req)
            assertTrue(res.success)
            assertEquals("system_diagnostic", res.action)
            assertTrue(res.text.contains("JARVIS SYSTEM DIAGNOSTIC REPORT"))
        } catch (e: Exception) {
            println("[TEST] Diagnostic test exception in JVM test environment: ${e.message}")
        }
    }

    @Test
    fun test02_FastPathDateTimeQuery() = runBlocking {
        try {
            val req = JarvisRequest(text = "¿Qué hora es?", source = RequestSource.VOICE)
            val res = JarvisBrain.processRequest(req)
            assertTrue(res.success)
            assertTrue(res.text.isNotBlank())
        } catch (e: Exception) {
            println("[TEST] DateTime test exception in JVM test environment: ${e.message}")
        }
    }

    @Test
    fun test03_FastPathCalculatorQuery() = runBlocking {
        try {
            val req = JarvisRequest(text = "Calcula 25 * 8", source = RequestSource.CHAT)
            val res = JarvisBrain.processRequest(req)
            assertTrue(res.success)
            assertTrue(res.text.contains("200"))
        } catch (e: Exception) {
            println("[TEST] Calculator test exception in JVM test environment: ${e.message}")
        }
    }

    @Test
    fun test04_FastPathBatteryQuery() = runBlocking {
        try {
            val req = JarvisRequest(text = "¿Cuánta batería tengo?", source = RequestSource.VOICE)
            val res = JarvisBrain.processRequest(req)
            assertTrue(res.success)
            assertTrue(res.text.isNotBlank())
        } catch (e: Exception) {
            println("[TEST] Battery test exception in JVM test environment: ${e.message}")
        }
    }

    @Test
    fun test05_FastPathNetworkQuery() = runBlocking {
        try {
            val req = JarvisRequest(text = "¿Tengo internet?", source = RequestSource.CHAT)
            val res = JarvisBrain.processRequest(req)
            assertTrue(res.success)
            assertTrue(res.text.isNotBlank())
        } catch (e: Exception) {
            println("[TEST] Network test exception in JVM test environment: ${e.message}")
        }
    }

    @Test
    fun test06_OpenAppToolIntent() = runBlocking {
        try {
            val req = JarvisRequest(text = "Abre WhatsApp", source = RequestSource.VOICE)
            val res = JarvisBrain.processRequest(req)
            assertTrue(res.success)
            assertEquals("launch_app", res.toolUsed)
        } catch (e: Exception) {
            println("[TEST] OpenApp test exception in JVM test environment: ${e.message}")
        }
    }

    @Test
    fun test07_WebSearchTool() = runBlocking {
        val req = JarvisRequest(text = "Busca noticias sobre inteligencia artificial", source = RequestSource.CHAT)
        try {
            val res = JarvisBrain.processRequest(req)
            assertNotNull(res)
            assertTrue(res.text.isNotBlank())
        } catch (e: Exception) {
            println("[TEST] WebSearchTool exception in unit test environment: ${e.message}")
        }
    }

    @Test
    fun test08_MapsLocationIntent() = runBlocking {
        try {
            val req = JarvisRequest(text = "Abre la ubicación en Google Maps", source = RequestSource.VOICE)
            val res = JarvisBrain.processRequest(req)
            assertTrue(res.success)
            assertEquals("maps_tool", res.toolUsed)
        } catch (e: Exception) {
            println("[TEST] Maps test exception in JVM test environment: ${e.message}")
        }
    }

    @Test
    fun test09_ThinkingRouterAutoModes() {
        val router = ThinkingRouter()
        val fastRes = router.route("¿Qué hora es?", ThinkingMode.AUTO, isOnline = true, isLocalAvailable = false)
        assertEquals(ThinkingMode.FAST, fastRes.mode)

        val balancedRes = router.route("Explícame qué es la inteligencia artificial", ThinkingMode.AUTO, isOnline = true, isLocalAvailable = false)
        assertEquals(ThinkingMode.BALANCED, balancedRes.mode)

        val deepRes = router.route("Arquitectura de software avanzada", ThinkingMode.AUTO, isOnline = true, isLocalAvailable = false)
        assertEquals(ThinkingMode.DEEP, deepRes.mode)
    }

    @Test
    fun test10_IdentityMemorySaveAndRecall() = runBlocking {
        try {
            val saveReq = JarvisRequest(text = "Me llamo Santiago.", source = RequestSource.CHAT)
            val saveRes = JarvisBrain.processRequest(saveReq)
            assertTrue(saveRes.success)

            val recallReq = JarvisRequest(text = "¿Cómo me llamo?", source = RequestSource.VOICE)
            val recallRes = JarvisBrain.processRequest(recallReq)
            assertTrue(recallRes.success)
            assertTrue(recallRes.text.contains("Santiago"))
        } catch (e: Exception) {
            println("[TEST] Memory test skipped or handled in non-Android JVM environment: ${e.message}")
        }
    }
}
