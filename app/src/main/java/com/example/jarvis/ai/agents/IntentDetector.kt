package com.example.jarvis.ai.agents

interface IntentDetector {
    suspend fun detectIntent(input: String): String
}
