package com.example.jarvis.ai.communications

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RealCommunicationProvidersTest {

    private lateinit var telegramProvider: TelegramProvider
    private lateinit var whatsappProvider: WhatsAppBusinessProvider
    private lateinit var slackProvider: SlackProvider

    @Before
    fun setUp() {
        telegramProvider = TelegramProvider()
        whatsappProvider = WhatsAppBusinessProvider()
        slackProvider = SlackProvider()
    }

    @Test(expected = SecurityException::class)
    fun testTelegramUnconfiguredThrowsSecurityException() {
        runBlocking {
            telegramProvider.getConversations()
        }
    }

    @Test(expected = SecurityException::class)
    fun testWhatsAppUnconfiguredThrowsSecurityException() {
        runBlocking {
            whatsappProvider.getConversations()
        }
    }

    @Test(expected = SecurityException::class)
    fun testSlackUnconfiguredThrowsSecurityException() {
        runBlocking {
            slackProvider.getConversations()
        }
    }

    @Test
    fun testTelegramConfiguredAndSendMessageWithConfirmation() = runBlocking {
        telegramProvider.setBotToken("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11")
        assertTrue(telegramProvider.isConfigured())

        val msg = CommunicationMessage(conversationId = "tg_chat_1", provider = "telegram", sender = "user", body = "Hello Telegram")
        val success = telegramProvider.sendMessage(msg, confirmed = true)
        assertTrue(success)
    }

    @Test
    fun testWhatsAppConfiguredAndSendMessageWithConfirmation() = runBlocking {
        whatsappProvider.setCredentials("token_sample_123", "phone_id_999")
        assertTrue(whatsappProvider.isConfigured())

        val msg = CommunicationMessage(conversationId = "wa_phone_1", provider = "whatsapp", sender = "user", body = "Hello WhatsApp")
        val success = whatsappProvider.sendMessage(msg, confirmed = true)
        assertTrue(success)
    }

    @Test
    fun testSlackConfiguredAndSendMessageWithConfirmation() = runBlocking {
        slackProvider.setBotToken("xoxb-123456789-987654321-sampletoken")
        assertTrue(slackProvider.isConfigured())

        val msg = CommunicationMessage(conversationId = "#general", provider = "slack", sender = "user", body = "Hello Slack")
        val success = slackProvider.sendMessage(msg, confirmed = true)
        assertTrue(success)
    }
}
