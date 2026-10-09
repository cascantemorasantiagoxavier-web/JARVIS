package com.example.jarvis.ai.automation

import com.example.jarvis.ai.agents.AgentRegistry
import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.agents.ToolRegistry
import com.example.jarvis.ai.tools.DefaultRiskEvaluator
import com.example.jarvis.ai.tools.RiskEvaluator
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventType
import com.example.jarvis.domain.tools.RiskLevel

class AutomationEngine(
    private val agentRegistry: AgentRegistry = DefaultAgentRegistry(),
    private val toolRegistry: ToolRegistry = DefaultToolRegistry(),
    private val riskEvaluator: RiskEvaluator = DefaultRiskEvaluator(),
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) {
    private val automations = mutableMapOf<String, Automation>()
    private val executedFingerprints = mutableSetOf<String>()
    private val executionHistory = mutableListOf<AutomationExecution>()

    fun registerAutomation(automation: Automation) {
        automations[automation.id] = automation
    }

    suspend fun handleEvent(event: AppEvent, forceManual: Boolean = false): List<AutomationExecution> {
        val results = mutableListOf<AutomationExecution>()
        val eventData = event.data.mapKeys { it.key } + mapOf("eventType" to event.type.name)

        for (automation in automations.values) {
            if (!automation.enabled) continue

            val matchesTrigger = when (automation.trigger.type) {
                TriggerType.EVENT -> automation.trigger.eventType.equals(event.type.name, ignoreCase = true) && automation.trigger.customPredicate(eventData)
                TriggerType.MANUAL -> forceManual
                TriggerType.SCHEDULE -> forceManual || event.type == EventType.ROUTINE_TRIGGERED
            }

            if (!matchesTrigger) continue

            // Evaluate conditions deterministically
            val conditionsMet = automation.conditions.all { it.evaluate(eventData) }
            if (!conditionsMet) {
                logger.i("AutomationEngine", "Automation '${automation.name}' conditions not met.")
                continue
            }

            // Idempotency check via fingerprint
            val fingerprint = "${automation.id}_${event.id}_${System.currentTimeMillis() / 60000}"
            if (executedFingerprints.contains(fingerprint)) {
                logger.i("AutomationEngine", "Duplicate automation execution suppressed by idempotency check.")
                continue
            }
            executedFingerprints.add(fingerprint)

            // Execute actions
            var success = true
            var lastOutput = "Automation executed successfully"
            var errorMsg: String? = null
            var status = AutomationExecutionStatus.SUCCESS

            val actionCount = automation.actions.size.coerceAtMost(automation.maxActionsPerRun)
            for (i in 0 until actionCount) {
                val action = automation.actions[i]
                try {
                    val tool = toolRegistry.getTool(action.targetToolName)
                    if (tool != null) {
                        val riskEval = riskEvaluator.evaluate(tool, action.parameters)
                        if (!riskEval.isPermitted || (tool.riskLevel == RiskLevel.DESTRUCTIVE && action.requiresConfirmation)) {
                            status = AutomationExecutionStatus.BLOCKED_BY_RISK
                            success = false
                            errorMsg = "Blocked by RiskEvaluator or requires confirmation: ${riskEval.reasoning}"
                            auditLogger.logAction(
                                AuditLogEntry(
                                    requestId = automation.id,
                                    intent = "AUTOMATION_${action.type}",
                                    toolName = tool.name,
                                    riskLevel = tool.riskLevel,
                                    resultSummary = errorMsg,
                                    isSuccess = false,
                                    requiresUserConfirmation = true
                                )
                            )
                            break
                        }
                        lastOutput = tool.execute(action.parameters)
                        auditLogger.logAction(
                            AuditLogEntry(
                                requestId = automation.id,
                                intent = "AUTOMATION_${action.type}",
                                toolName = tool.name,
                                riskLevel = tool.riskLevel,
                                resultSummary = lastOutput,
                                isSuccess = true
                            )
                        )
                    } else if (action.agentId != null) {
                        val agent = agentRegistry.getAgent(action.agentId)
                        if (agent != null) {
                            lastOutput = agent.process(eventData.values.firstOrNull() ?: "Automation triggered")
                        } else {
                            success = false
                            errorMsg = "Agent ${action.agentId} not found"
                            status = AutomationExecutionStatus.FAILED
                            break
                        }
                    }
                } catch (e: Exception) {
                    success = false
                    errorMsg = e.message
                    status = AutomationExecutionStatus.FAILED
                    logger.e("AutomationEngine", "Error executing automation action", e)
                    break
                }
            }

            val execution = AutomationExecution(
                automationId = automation.id,
                status = status,
                output = lastOutput,
                error = errorMsg
            )
            executionHistory.add(execution)
            results.add(execution)
        }
        return results
    }

    fun getExecutionHistory(): List<AutomationExecution> {
        return executionHistory.toList()
    }
}
