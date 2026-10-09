package com.example.jarvis.domain.ai

import android.util.Log
import com.example.jarvis.ai.agents.normalizeQuery

enum class ThinkingMode {
    FAST,
    BALANCED,
    DEEP,
    AUTO
}

data class ThinkingDecision(
    val mode: ThinkingMode,
    val reason: String,
    val provider: String
)

class ThinkingRouter {
    fun route(input: String, requestedMode: ThinkingMode, isOnline: Boolean, isLocalAvailable: Boolean): ThinkingDecision {
        val norm = input.normalizeQuery().trim()

        val actualMode = if (requestedMode == ThinkingMode.AUTO) {
            when {
                norm == "que hora es" || norm == "hora" ||
                norm == "que fecha es" || norm == "fecha" ||
                norm == "que dia es hoy" ||
                norm.contains("bateria") || norm.contains("internet") || norm.contains("conexion") ||
                norm.startsWith("calcula ") || norm.startsWith("abre ") || norm.startsWith("abrir ") -> ThinkingMode.FAST

                norm.contains("arquitectura") || norm.contains("disena") || norm.contains("programa") ||
                norm.contains("codigo") || norm.contains("analiza") -> ThinkingMode.DEEP

                else -> ThinkingMode.BALANCED
            }
        } else {
            requestedMode
        }

        val provider = when {
            actualMode == ThinkingMode.FAST -> "LOCAL_TOOL_OR_FAST_GEMINI"
            !isOnline && isLocalAvailable -> "LOCAL_GEMMA"
            actualMode == ThinkingMode.DEEP -> "GEMINI_REASONING_PRO"
            else -> "GEMINI_BALANCED"
        }

        val reason = "Routed input '$input' to ${actualMode.name} mode based on intent complexity."
        try {
            Log.i("JARVIS_THINKING", "[JARVIS_THINKING] mode=${actualMode.name} reason=$reason provider=$provider")
        } catch (_: Throwable) {
            println("[JARVIS_THINKING] mode=${actualMode.name} reason=$reason provider=$provider")
        }
        return ThinkingDecision(mode = actualMode, reason = reason, provider = provider)
    }
}
