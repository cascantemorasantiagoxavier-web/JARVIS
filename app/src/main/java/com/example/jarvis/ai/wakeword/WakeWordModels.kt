package com.example.jarvis.ai.wakeword

enum class WakeWordState {
    DISABLED,
    LISTENING,
    PAUSED,
    DETECTED,
    COMMAND_LISTENING,
    PROCESSING,
    SPEAKING
}

data class WakeWordEvent(
    val keyword: String = "JARVIS",
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float = 0.95f
)
