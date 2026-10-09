package com.example.jarvis.ai.context

interface ContextRanker {
    fun rank(contextItems: List<Map<String, Any>>): List<Map<String, Any>>
}
