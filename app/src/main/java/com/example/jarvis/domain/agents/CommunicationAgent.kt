package com.example.jarvis.domain.agents

interface CommunicationAgent : Agent {
    suspend fun searchCommunications(query: String): String
    suspend fun prepareDraft(provider: String, conversationId: String, body: String): String
    suspend fun sendMessage(provider: String, conversationId: String, body: String, confirmed: Boolean): Boolean
}
