package com.example.jarvis.ai.context

class DefaultContextRanker : ContextRanker {
    override fun rank(contextItems: List<Map<String, Any>>): List<Map<String, Any>> {
        return contextItems.sortedByDescending { it["level"].toString() }
    }
}
