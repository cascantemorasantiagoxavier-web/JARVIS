package com.example.jarvis.ai.tools

import com.example.jarvis.ai.iot.provider.HomeAssistantConfig
import com.example.jarvis.ai.iot.provider.HomeAssistantProvider
import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.domain.iot.*
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class IoTTool(
    private val provider: IoTProvider = HomeAssistantProvider(HomeAssistantConfig()),
    private val riskEvaluator: RiskEvaluator = DefaultRiskEvaluator(),
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) : Tool {

    override val name = "iot_tool"
    override val description = "Controla y consulta dispositivos IoT inteligentes del hogar (luces, interruptores, clima, cerraduras, etc.)"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): String {
        val action = params["action"]?.toString()?.lowercase() ?: params["operation"]?.toString()?.lowercase() ?: "get_state"
        val entityId = params["entity_id"]?.toString() ?: params["device"]?.toString() ?: params["query"]?.toString() ?: ""

        if (entityId.isBlank()) {
            return "Por favor, especifica el dispositivo o entidad IoT (ej. light.living_room)."
        }

        // 1. Resolve domain and service
        val domain = entityId.substringBefore(".", entityId)
        val service = when (action) {
            "turn_on", "encender" -> "turn_on"
            "turn_off", "apagar" -> "turn_off"
            "toggle", "alternar" -> "toggle"
            "unlock", "abrir_cerradura" -> "unlock"
            "lock", "cerrar_cerradura" -> "lock"
            else -> "turn_on"
        }

        val operationType = when (action) {
            "get_state", "estado", "leer" -> IoTOperationType.READ_STATE
            "turn_on", "encender" -> IoTOperationType.TURN_ON
            "turn_off", "apagar" -> IoTOperationType.TURN_OFF
            "toggle" -> IoTOperationType.TOGGLE
            else -> IoTOperationType.EXECUTE_SERVICE
        }

        val opParams = params.filterKeys { it != "action" && it != "operation" && it != "entity_id" && it != "device" && it != "query" }
        val operation = IoTOperation(
            type = operationType,
            domain = domain,
            service = service,
            parameters = opParams
        )

        // 2. Risk Evaluation
        val riskEval = riskEvaluator.evaluate(this, params)
        if (!riskEval.isPermitted || (operationType == IoTOperationType.EXECUTE_SERVICE && riskEval.requiresUserConfirmation)) {
            auditLogger.logAction(
                AuditLogEntry(
                    requestId = entityId,
                    intent = "IOT_OPERATION_$action",
                    toolName = name,
                    riskLevel = riskLevel,
                    resultSummary = "Blocked by RiskEvaluator: ${riskEval.reasoning}",
                    isSuccess = false,
                    requiresUserConfirmation = true
                )
            )
            return "Operación bloqueada por seguridad o requiere confirmación de usuario: ${riskEval.reasoning}"
        }

        // 3. Execute via provider
        val result = if (operationType == IoTOperationType.READ_STATE) {
            provider.getState(entityId)
        } else {
            provider.executeOperation(entityId, operation)
        }

        return if (result.isSuccess) {
            val output = result.getOrNull() ?: "Operación completada con éxito."
            auditLogger.logAction(
                AuditLogEntry(
                    requestId = entityId,
                    intent = "IOT_OPERATION_$action",
                    toolName = name,
                    riskLevel = riskLevel,
                    resultSummary = output,
                    isSuccess = true
                )
            )
            output
        } else {
            val error = result.exceptionOrNull()?.message ?: "Error desconocido en dispositivo IoT"
            logger.e("IoTTool", "Error executing IoT operation: $error")
            auditLogger.logAction(
                AuditLogEntry(
                    requestId = entityId,
                    intent = "IOT_OPERATION_$action",
                    toolName = name,
                    riskLevel = riskLevel,
                    resultSummary = error,
                    isSuccess = false
                )
            )
            "No pude completar la operación IoT: $error"
        }
    }
}
