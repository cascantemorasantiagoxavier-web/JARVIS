package com.example.jarvis.ai.context

enum class ContextLevel(val level: Int) {
    LEVEL_0_RAW_INPUT(0),
    LEVEL_0_IDENTITY(0),
    LEVEL_1_SESSION_HISTORY(1),
    LEVEL_1_SESSION(1),
    LEVEL_2_WORKING_MEMORY(2),
    LEVEL_3_LONG_TERM_MEMORY(3),
    LEVEL_4_OBSIDIAN_VAULT(4),
    LEVEL_4_DOMAIN(4),
    LEVEL_5_HISTORY(5),
    LEVEL_5_GLOBAL_SYSTEM(5)
}
