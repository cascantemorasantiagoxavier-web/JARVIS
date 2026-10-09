package com.example.jarvis.ai.context

import com.example.jarvis.ai.orchestrator.models.IntentType

class DefaultContextRouter : ContextRouter {
    override suspend fun route(request: ContextRequest): List<ContextLevel> {
        return ContextLevel.entries.filter { it.level <= request.maxLevel.level }
    }

    override fun routeRequirements(intentType: IntentType, input: String): ContextRequirement {
        return when (intentType) {
            IntentType.CONVERSATION, IntentType.GENERAL, IntentType.UNKNOWN -> ContextRequirement(
                levels = listOf(ContextLevel.LEVEL_0_IDENTITY, ContextLevel.LEVEL_1_SESSION),
                needsMemory = false,
                needsKnowledge = false,
                maxTokens = 1024
            )
            IntentType.TASK_CREATE, IntentType.TASK_UPDATE, IntentType.PRODUCTIVITY -> ContextRequirement(
                levels = listOf(ContextLevel.LEVEL_0_IDENTITY, ContextLevel.LEVEL_1_SESSION, ContextLevel.LEVEL_4_DOMAIN),
                needsMemory = false,
                needsKnowledge = false,
                needsTasks = true,
                maxTokens = 1536
            )
            IntentType.MEMORY_QUERY, IntentType.MEMORY_WRITE, IntentType.MEMORY -> ContextRequirement(
                levels = listOf(ContextLevel.LEVEL_0_IDENTITY, ContextLevel.LEVEL_1_SESSION, ContextLevel.LEVEL_2_WORKING_MEMORY, ContextLevel.LEVEL_3_LONG_TERM_MEMORY),
                needsMemory = true,
                needsKnowledge = false,
                maxTokens = 2048
            )
            IntentType.OBSIDIAN_SEARCH, IntentType.OBSIDIAN_READ, IntentType.OBSIDIAN_WRITE, IntentType.OBSIDIAN -> ContextRequirement(
                levels = listOf(ContextLevel.LEVEL_0_IDENTITY, ContextLevel.LEVEL_1_SESSION, ContextLevel.LEVEL_4_OBSIDIAN_VAULT),
                needsMemory = false,
                needsKnowledge = true,
                maxTokens = 3072
            )
            IntentType.PROJECT_QUERY, IntentType.PLANNING -> ContextRequirement(
                levels = listOf(ContextLevel.LEVEL_0_IDENTITY, ContextLevel.LEVEL_1_SESSION, ContextLevel.LEVEL_2_WORKING_MEMORY, ContextLevel.LEVEL_3_LONG_TERM_MEMORY, ContextLevel.LEVEL_4_OBSIDIAN_VAULT),
                needsMemory = true,
                needsKnowledge = true,
                needsProject = true,
                maxTokens = 4096
            )
            else -> ContextRequirement(
                levels = listOf(ContextLevel.LEVEL_0_IDENTITY, ContextLevel.LEVEL_1_SESSION),
                maxTokens = 2048
            )
        }
    }
}
