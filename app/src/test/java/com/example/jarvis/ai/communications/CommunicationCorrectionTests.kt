package com.example.jarvis.ai.communications

import com.example.jarvis.ai.context.ContextBudget
import com.example.jarvis.ai.context.ContextItem
import com.example.jarvis.ai.context.ContextLevel
import com.example.jarvis.ai.context.ContextPriority
import com.example.jarvis.ai.tools.CommunicationDraftTool
import com.example.jarvis.ai.tools.CommunicationSearchTool
import com.example.jarvis.ai.tools.CommunicationSendTool
import com.example.jarvis.domain.tools.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommunicationCorrectionTests {

    private lateinit var engine: CommunicationsEngine
    private lateinit var searchTool: CommunicationSearchTool
    private lateinit var draftTool: CommunicationDraftTool
    private lateinit var sendTool: CommunicationSendTool

    @Before
    fun setUp() {
        engine = CommunicationsEngine()
        searchTool = CommunicationSearchTool(engine)
        draftTool = CommunicationDraftTool(engine)
        sendTool = CommunicationSendTool(engine)
    }

    @Test
    fun test1_CommunicationRiskLevels() {
        assertEquals(RiskLevel.READ, searchTool.riskLevel)
        assertEquals(RiskLevel.WRITE, draftTool.riskLevel)
        assertEquals(RiskLevel.DESTRUCTIVE, sendTool.riskLevel)
    }

    @Test(expected = SecurityException::class)
    fun test2_SendWithoutConfirmationFails() {
        runBlocking {
            val msg = CommunicationMessage(conversationId = "wa_conv_1", provider = "whatsapp", sender = "user", body = "Hello")
            engine.sendMessage("whatsapp", msg, confirmed = false, token = null)
        }
    }

    @Test
    fun test3_ConfirmationIntegrityValidation() = runBlocking {
        val msg = CommunicationMessage(conversationId = "wa_conv_1", provider = "whatsapp", sender = "user", body = "Hello Carlos")
        val token = engine.generateConfirmationToken(msg)

        // Valid send
        val success = engine.sendMessage("whatsapp", msg, confirmed = true, token = token)
        assertTrue(success)

        // Modified message body invalidates confirmation token
        val modifiedMsg = msg.copy(body = "Hello Carlos, modified content")
        try {
            engine.sendMessage("whatsapp", modifiedMsg, confirmed = true, token = token)
            fail("Expected SecurityException due to content modification after confirmation")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("invalid or content was modified") == true)
        }
    }

    @Test
    fun test6_ConfirmationIntegrityAllFields() = runBlocking {
        val msg = CommunicationMessage(conversationId = "wa_conv_1", provider = "whatsapp", sender = "user", recipients = listOf("carlos"), body = "Hello")
        val token = engine.generateConfirmationToken(msg)

        // Change recipient
        val msgChangeRec = msg.copy(recipients = listOf("juan"))
        try {
            engine.sendMessage("whatsapp", msgChangeRec, confirmed = true, token = token)
            fail("Expected failure when recipient changes")
        } catch (_: SecurityException) {}

        // Change conversation
        val msgChangeConv = msg.copy(conversationId = "other_conv")
        try {
            engine.sendMessage("whatsapp", msgChangeConv, confirmed = true, token = token)
            fail("Expected failure when conversation changes")
        } catch (_: SecurityException) {}
    }

    @Test(expected = UnsupportedOperationException::class)
    fun test4_CapabilityValidation() {
        runBlocking {
            val incapableProvider = object : CommunicationProvider {
                override val providerName = "incapable"
                override val capabilities = CommunicationCapabilities(canSendMessages = false)
                override suspend fun getConversations() = emptyList<Conversation>()
                override suspend fun getConversation(id: String) = null
                override suspend fun searchMessages(query: String) = emptyList<CommunicationMessage>()
                override suspend fun getMessages(conversationId: String, limit: Int) = emptyList<CommunicationMessage>()
                override suspend fun createDraft(draft: CommunicationDraft) = draft
                override suspend fun sendMessage(message: CommunicationMessage, confirmed: Boolean) = true
            }
            val restrictedEngine = CommunicationsEngine(listOf(incapableProvider))
            val msg = CommunicationMessage(conversationId = "c1", provider = "incapable", sender = "user", body = "test")
            val token = ConfirmationToken("incapable", "c1", emptyList(), "test")
            restrictedEngine.sendMessage("incapable", msg, confirmed = true, token = token)
        }
    }

    @Test
    fun test5_ContextEngineBudgetIntegration() {
        val budget = ContextBudget(maxTokens = 200, reservedTokens = 50)
        val items = listOf(
            ContextItem("1", ContextLevel.LEVEL_0_IDENTITY, ContextPriority.CRITICAL, "Identity rules", "system", 30),
            ContextItem("2", ContextLevel.LEVEL_4_DOMAIN, ContextPriority.NORMAL, "Communication history snippet 1...", "comm", 120),
            ContextItem("3", ContextLevel.LEVEL_5_HISTORY, ContextPriority.LOW, "Very old chat log archive...", "history", 300)
        )
        val optimized = budget.optimize(items)
        assertTrue(optimized.any { it.priority == ContextPriority.CRITICAL })
        assertFalse(optimized.any { it.id == "3" }) // dropped due to budget
    }
}
