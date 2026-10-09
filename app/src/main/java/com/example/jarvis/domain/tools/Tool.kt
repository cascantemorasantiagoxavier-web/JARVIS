package com.example.jarvis.domain.tools

interface Tool {
    val name: String
    val description: String
    val riskLevel: RiskLevel
    suspend fun execute(params: Map<String, Any>): String
}
