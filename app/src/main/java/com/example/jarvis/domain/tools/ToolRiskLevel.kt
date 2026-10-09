package com.example.jarvis.domain.tools

enum class ToolRiskLevel(val requiresExplicitConfirmation: Boolean) {
    READ(false),
    WRITE(false),
    EXECUTE(true),
    DESTRUCTIVE(true)
}
