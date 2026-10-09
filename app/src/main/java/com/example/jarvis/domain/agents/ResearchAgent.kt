package com.example.jarvis.domain.agents

interface ResearchAgent : Agent {
    suspend fun researchTopic(topic: String): String
}
