package com.example.jarvis.ai.tools

import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

data class RiskEvaluation(
    val isPermitted: Boolean,
    val requiresUserConfirmation: Boolean,
    val reasoning: String
)

interface RiskEvaluator {
    suspend fun evaluate(tool: Tool, params: Map<String, Any>): RiskEvaluation
}

class DefaultRiskEvaluator : RiskEvaluator {
    override suspend fun evaluate(tool: Tool, params: Map<String, Any>): RiskEvaluation {
        return when (tool.riskLevel) {
            RiskLevel.READ -> RiskEvaluation(
                isPermitted = true,
                requiresUserConfirmation = false,
                reasoning = "Read operation auto-approved"
            )
            RiskLevel.WRITE -> RiskEvaluation(
                isPermitted = true,
                requiresUserConfirmation = false,
                reasoning = "Write operation permitted under standard config"
            )
            RiskLevel.EXECUTE -> RiskEvaluation(
                isPermitted = true,
                requiresUserConfirmation = true,
                reasoning = "Execution operation requires security evaluation"
            )
            RiskLevel.DESTRUCTIVE -> RiskEvaluation(
                isPermitted = false,
                requiresUserConfirmation = true,
                reasoning = "Destructive operation requires explicit user confirmation and is restricted"
            )
        }
    }
}
