package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.agents.ToolRegistry
import com.example.jarvis.ai.orchestrator.models.*
import com.example.jarvis.ai.tools.RiskEvaluator
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.domain.tools.RiskLevel

interface VoiceOrchestratorBridge {
    suspend fun processVoiceCommand(command: VoiceCommand): VoiceResponse
}

class DefaultVoiceOrchestratorBridge(
    private val orchestrator: OrchestratorBrain,
    private val riskEvaluator: RiskEvaluator,
    private val toolRegistry: ToolRegistry,
    private val logger: AppLogger = ConsoleLogger()
) : VoiceOrchestratorBridge {

    override suspend fun processVoiceCommand(command: VoiceCommand): VoiceResponse {
        val request = command.toOrchestratorRequest()
        logger.i("VoiceBridge", "Processing voice command from session ${command.sessionId}: ${command.text}")

        val response = orchestrator.processRequest(request)
        val userFacing = UserResponseMapper.map(response)

        // Security check: Verify risk level and permission policy for selected tool
        val toolName = response.toolSelection?.toolName
        if (toolName != null) {
            val tool = toolRegistry.getTool(toolName)
            if (tool != null) {
                val evaluation = riskEvaluator.evaluate(tool, response.toolSelection.parameters)
                if (!evaluation.isPermitted || (tool.riskLevel == RiskLevel.DESTRUCTIVE && evaluation.requiresUserConfirmation)) {
                    val securityError = OrchestratorError(
                        type = ErrorType.PERMISSION_DENIED,
                        userMessage = "Esta acción requiere autorización del usuario o un permiso especial no concedido.",
                        technicalDetails = "Blocked by RiskEvaluator: ${evaluation.reasoning}"
                    )
                    logger.e("VoiceBridge", "Security restriction: ${evaluation.reasoning}")
                    return VoiceResponse(
                        text = securityError.userMessage,
                        responseType = VoiceResponseType.ERROR,
                        error = securityError
                    )
                }
            }
        }

        return VoiceResponse(
            text = userFacing.text,
            responseType = if (response.isSuccess) VoiceResponseType.TOOL_RESULT else VoiceResponseType.ERROR,
            error = response.error
        )
    }
}
