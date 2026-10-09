package com.example.jarvis.ai.tools

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class WebSearchAndToolsIntegrationTest {

    private var backendProcess: Process? = null

    @Before
    fun setUp() {
        try {
            val rootDir = File(System.getProperty("user.dir")!!)
            val backendDir = if (File(rootDir, "backend").exists()) {
                File(rootDir, "backend")
            } else {
                File(rootDir, "../backend")
            }
            if (backendDir.exists()) {
                val pb = ProcessBuilder("node", "src/index.js")
                pb.directory(backendDir)
                backendProcess = pb.start()
                Thread.sleep(3500)
            }
        } catch (e: Exception) {
            println("[JARVIS_E2E] Warning starting backend: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        backendProcess?.destroy()
    }

    @Test
    fun testWebSearchToolExecution() = runBlocking {
        val tool = WebSearchTool()
        val result = tool.execute(mapOf("query" to "latest news Costa Rica"))

        assertNotNull(result)
        assertTrue(result.isNotBlank())
        println("[WEB_TEST] WebSearchTool result:\n$result")
    }

    @Test
    fun testOpenWebPageToolExecution() = runBlocking {
        val tool = OpenWebPageTool()
        val result = tool.execute(mapOf("url" to "https://www.google.com"))

        assertNotNull(result)
        assertTrue(result.isNotBlank())
        println("[WEB_TEST] OpenWebPageTool result:\n$result")
    }
}
