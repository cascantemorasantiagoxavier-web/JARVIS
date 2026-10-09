package com.example.jarvis.ai.context

data class ContextRequest(
    val query: String,
    val maxLevel: ContextLevel = ContextLevel.LEVEL_5_GLOBAL_SYSTEM
)
