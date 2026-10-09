package com.example.jarvis.ai.integration

import com.example.jarvis.ai.calendar.CalendarEvent
import com.example.jarvis.ai.calendar.GoogleCalendarProvider
import com.example.jarvis.ai.calendar.GoogleOAuthToken
import com.example.jarvis.ai.communications.*
import com.example.jarvis.ai.email.EmailMessage
import com.example.jarvis.ai.email.GmailProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JointPhase24ProductionVerificationTest {

    private lateinit var calendarProvider: GoogleCalendarProvider
    private lateinit var gmailProvider: GmailProvider
    private lateinit var telegramProvider: TelegramProvider
    private lateinit var whatsAppProvider: WhatsAppBusinessProvider
    private lateinit var slackProvider: SlackProvider

    @Before
    fun setUp() {
        calendarProvider = GoogleCalendarProvider()
        gmailProvider = GmailProvider()
        telegramProvider = TelegramProvider()
        whatsAppProvider = WhatsAppBusinessProvider()
        slackProvider = SlackProvider()
    }

    @Test
    fun testAllProvidersUnauthenticatedAccess() {
        runBlocking {
            try {
                calendarProvider.getEvents(0, 1000)
                fail("GoogleCalendarProvider should fail when unauthenticated")
            } catch (_: SecurityException) {}

            try {
                gmailProvider.searchMessages("test")
                fail("GmailProvider should fail when unauthenticated")
            } catch (_: SecurityException) {}

            try {
                telegramProvider.getConversations()
                fail("TelegramProvider should fail when unconfigured")
            } catch (_: SecurityException) {}

            try {
                whatsAppProvider.getConversations()
                fail("WhatsAppBusinessProvider should fail when unconfigured")
            } catch (_: SecurityException) {}

            try {
                slackProvider.getConversations()
                fail("SlackProvider should fail when unconfigured")
            } catch (_: SecurityException) {}
        }
    }

    @Test
    fun testAllProvidersAuthenticatedFlows() = runBlocking {
        val googleToken = GoogleOAuthToken("verified_token_123", expiresIn = 3600)

        // 1. Google Calendar
        calendarProvider.authenticateWithOAuth(googleToken)
        assertTrue(calendarProvider.isAuthenticated())
        val calEvent = calendarProvider.createEvent(CalendarEvent(title = "Production Review", startTime = 100, endTime = 200))
        assertEquals("google_calendar", calEvent.source)

        // 2. Gmail
        gmailProvider.authenticateWithOAuth(googleToken)
        assertTrue(gmailProvider.isAuthenticated())
        val emailDraft = gmailProvider.createDraft(EmailMessage(from = "u@jarvis.com", to = "c@jarvis.com", subject = "Joint Test", body = "Body"))
        assertTrue(emailDraft.isDraft)

        // 3. Telegram
        telegramProvider.setBotToken("123456:AABBCCDDEEFF")
        assertTrue(telegramProvider.isConfigured())
        val tgSent = telegramProvider.sendMessage(CommunicationMessage(conversationId = "c1", provider = "telegram", sender = "u", body = "Msg"), confirmed = true)
        assertTrue(tgSent)

        // 4. WhatsApp
        whatsAppProvider.setCredentials("wa_token_123", "wa_phone_999")
        assertTrue(whatsAppProvider.isConfigured())
        val waSent = whatsAppProvider.sendMessage(CommunicationMessage(conversationId = "c2", provider = "whatsapp", sender = "u", body = "Msg"), confirmed = true)
        assertTrue(waSent)

        // 5. Slack
        slackProvider.setBotToken("xoxb-123-456-789")
        assertTrue(slackProvider.isConfigured())
        val slSent = slackProvider.sendMessage(CommunicationMessage(conversationId = "#gen", provider = "slack", sender = "u", body = "Msg"), confirmed = true)
        assertTrue(slSent)
    }

    @Test
    fun testUnconfirmedSendBlockedAcrossAllProviders() {
        runBlocking {
            telegramProvider.setBotToken("123:abc")
            whatsAppProvider.setCredentials("token", "phone")
            slackProvider.setBotToken("xoxb-token")

            try {
                telegramProvider.sendMessage(CommunicationMessage(conversationId = "1", provider = "telegram", sender = "u", body = "test"), confirmed = false)
                fail("Telegram send without confirmation should be blocked")
            } catch (_: SecurityException) {}

            try {
                whatsAppProvider.sendMessage(CommunicationMessage(conversationId = "1", provider = "whatsapp", sender = "u", body = "test"), confirmed = false)
                fail("WhatsApp send without confirmation should be blocked")
            } catch (_: SecurityException) {}

            try {
                slackProvider.sendMessage(CommunicationMessage(conversationId = "1", provider = "slack", sender = "u", body = "test"), confirmed = false)
                fail("Slack send without confirmation should be blocked")
            } catch (_: SecurityException) {}
        }
    }
}
