package com.example.jarvis.domain.agents

interface CodingAgent : Agent {
    suspend fun reviewCode(code: String): String
}
