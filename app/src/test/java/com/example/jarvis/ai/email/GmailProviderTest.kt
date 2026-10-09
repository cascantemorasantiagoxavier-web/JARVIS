package com.example.jarvis.ai.email

import com.example.jarvis.ai.calendar.GoogleOAuthToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GmailProviderTest {

    private lateinit var provider: GmailProvider

    @Before
    fun setUp() {
        provider = GmailProvider()
    }

    @Test(expected = SecurityException::class)
    fun testUnauthenticatedAccessThrowsSecurityException() {
        runBlocking {
            provider.searchMessages("test query")
        }
    }

    @Test
    fun testAuthenticationAndOperations() = runBlocking {
        val token = GoogleOAuthToken(accessToken = "gmail_oauth_token_456", expiresIn = 3600L)
        provider.authenticateWithOAuth(token)

        assertTrue(provider.isAuthenticated())

        val draftMessage = EmailMessage(from = "user@jarvis.com", to = "carlos@example.com", subject = "Draft Test", body = "Draft content")
        val draft = provider.createDraft(draftMessage)

        assertTrue(draft.isDraft)
        assertEquals("Draft Test", draft.subject)

        val sent = provider.sendMessage(draft)
        assertTrue(sent)
    }

    @Test
    fun testTokenExpirationCheck() {
        val expiredToken = GoogleOAuthToken(accessToken = "expired_token", expiresIn = -100L)
        provider.authenticateWithOAuth(expiredToken)

        assertFalse(provider.isAuthenticated())
    }
}
