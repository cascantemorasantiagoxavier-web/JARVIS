package com.example.jarvis.ai.context

enum class ContextPriority(val weight: Int) {
    CRITICAL(4),
    HIGH(3),
    NORMAL(2),
    LOW(1)
}

data class ContextItem(
    val id: String,
    val level: ContextLevel,
    val priority: ContextPriority,
    val content: String,
    val source: String,
    val estimatedTokens: Int = content.length / 4
)
