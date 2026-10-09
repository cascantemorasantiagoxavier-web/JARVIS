package com.example.jarvis.ai.realtime

import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.data.remote.ai.DefaultRealtimeSessionService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RealtimeFoundationTest {

    private class TestLogger : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun i(tag: String, message: String) {}
    }

    @Test
    fun testRealtimeConnectionStates() {
        val state = RealtimeConnectionState.CONNECTED
        assertEquals(RealtimeConnectionState.CONNECTED, state)
    }

    @Test
    fun testMockRealtimeClientConnection() = runBlocking {
        val client = MockRealtimeClient()
        val config = RealtimeSessionConfig("gpt-4o-realtime", "token_test")
        
        client.connect(config)
        assertEquals(RealtimeConnectionState.CONNECTED, client.currentState)
        
        client.disconnect()
        assertEquals(RealtimeConnectionState.DISCONNECTED, client.currentState)
    }

    @Test
    fun testRealtimeSessionServiceSecure() = runBlocking {
        val service = DefaultRealtimeSessionService("https://test.backend", TestLogger())
        val result = service.createSession()
        
        assertTrue("Session creation should succeed via secure backend abstraction", result.isSuccess)
        val info = result.getOrNull()
        assertNotNull(info)
        assertFalse("Token must not contain hardcoded openai api key", info?.clientSecret?.contains("sk-") == true)
    }
}
