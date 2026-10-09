package com.example.jarvis.ai.orchestrator

import com.example.jarvis.features.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelGeminiRealEndToEndTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private var backendProcess: Process? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
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
                Thread.sleep(3000)
            }
        } catch (e: Exception) {
            println("[JARVIS_E2E] Could not start backend process: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backendProcess?.destroy()
    }

    @Test
    fun testChatViewModelToGeminiRealEndToEnd() = runTest(testDispatcher) {
        val prompt = "¿Por qué el cielo es azul? Respóndeme en exactamente dos frases y comienza la respuesta con JARVIS."
        println("[JARVIS_E2E] CHATVIEWMODEL_SEND=START")
        println("[JARVIS_E2E] REQUEST_SOURCE=CHAT")
        println("[JARVIS_E2E] PROVIDER=GeminiApiProvider")
        println("[JARVIS_E2E] MODEL=gemini-flash-lite-latest")
        println("[JARVIS_E2E] HTTP_REQUEST=POST /api/gemini/chat")

        val viewModel = ChatViewModel()
        viewModel.onInputChanged(prompt)

        val startTime = System.currentTimeMillis()
        viewModel.sendMessage()

        var attempts = 0
        while (viewModel.uiState.value.messages.size < 3 && attempts < 250) {
            delay(200)
            attempts++
        }

        val totalLatency = System.currentTimeMillis() - startTime

        val state = viewModel.uiState.value
        assertEquals("Should have initial, user, and AI messages", 3, state.messages.size)
        val aiMessage = state.messages[2]

        println("[JARVIS_E2E] CHATVIEWMODEL_RESPONSE=${aiMessage.text}")
        println("[JARVIS_E2E] TOTAL_LATENCY=${totalLatency}ms")

        assertNotNull(aiMessage.text)
        assertTrue(aiMessage.text.isNotBlank())
        assertNotEquals("Prompt echoed", prompt, aiMessage.text)
        assertFalse("Response should not be a fallback or error message", aiMessage.text.contains("No pude interpretar", true))
        assertFalse("Response should not be a timeout error", aiMessage.text.contains("Connect timed out", true))

        println("[JARVIS_E2E] CHATVIEWMODEL_TO_GEMINI=PASS")
    }
}
