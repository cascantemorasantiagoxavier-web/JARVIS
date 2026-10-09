package com.example.jarvis.ai.communications

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommunicationEngineTest {

    private lateinit var engine: CommunicationsEngine
    private lateinit var whatsApp: MockWhatsAppProvider
    private lateinit var telegram: MockTelegramProvider
    private lateinit var slack: MockSlackProvider

    @Before
    fun setUp() {
        whatsApp = MockWhatsAppProvider()
        telegram = MockTelegramProvider()
        slack = MockSlackProvider()
        engine = CommunicationsEngine(listOf(whatsApp, telegram, slack))
    }

    @Test
    fun testMultiProviderSearch() = runBlocking {
        val results = engine.searchAll("JARVIS")
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun testGetConversationsAcrossProviders() = runBlocking {
        val convs = engine.getConversations()
        assertEquals(3, convs.size)
    }

    @Test(expected = SecurityException::class)
    fun testSendMessageRequiresConfirmation() {
        runBlocking {
            val msg = CommunicationMessage(conversationId = "wa_conv_1", provider = "whatsapp", sender = "user", body = "Hello")
            whatsApp.sendMessage(msg, confirmed = false)
        }
    }

    @Test
    fun testSendMessageWithConfirmationSuccess() = runBlocking {
        val msg = CommunicationMessage(conversationId = "wa_conv_1", provider = "whatsapp", sender = "user", body = "Hello")
        val success = whatsApp.sendMessage(msg, confirmed = true)
        assertTrue(success)
    }
}
