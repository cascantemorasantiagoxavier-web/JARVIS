package com.example.jarvis.ai.context

data class ContextRequirement(
    val levels: List<ContextLevel>,
    val needsIdentity: Boolean = true,
    val needsSession: Boolean = true,
    val needsMemory: Boolean = false,
    val needsKnowledge: Boolean = false,
    val needsTasks: Boolean = false,
    val needsProject: Boolean = false,
    val maxTokens: Int = 2048
)
