package com.example.jarvis.domain.agents

interface Agent {
    val id: String
    val name: String
    suspend fun process(input: String): String
}
