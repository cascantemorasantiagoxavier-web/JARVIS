package com.example.jarvis.ai.automation

import com.example.jarvis.ai.agents.AgentRegistry
import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.agents.ToolRegistry
import com.example.jarvis.ai.tools.DefaultRiskEvaluator
import com.example.jarvis.ai.tools.RiskEvaluator
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.domain.events.AppEvent

class WorkflowEngine(
    private val agentRegistry: AgentRegistry = DefaultAgentRegistry(),
    private val toolRegistry: ToolRegistry = DefaultToolRegistry(),
    private val riskEvaluator: RiskEvaluator = DefaultRiskEvaluator(),
    private val logger: AppLogger = ConsoleLogger()
) {
    private val workflows = mutableMapOf<String, Workflow>()

    fun registerWorkflow(workflow: Workflow) {
        workflows[workflow.id] = workflow
    }

    suspend fun handleEvent(event: AppEvent): List<WorkflowExecutionResult> {
        val results = mutableListOf<WorkflowExecutionResult>()
        for (workflow in workflows.values) {
            if (workflow.trigger.eventType == event.type && workflow.trigger.filterPredicate(event)) {
                // Evaluate conditions
                val conditionsMet = workflow.conditions.all { it.check() }
                if (!conditionsMet) {
                    logger.i("WorkflowEngine", "Workflow '${workflow.name}' conditions not met for event ${event.type}")
                    continue
                }

                // Execute workflow steps
                var lastOutput = "Workflow started"
                var success = true
                var errorMsg: String? = null

                for (step in workflow.steps.sortedBy { it.stepNumber }) {
                    try {
                        val agent = agentRegistry.getAgent(step.agentId)
                        if (step.toolName != null) {
                            val tool = toolRegistry.getTool(step.toolName)
                            if (tool != null) {
                                val riskEval = riskEvaluator.evaluate(tool, step.parameters)
                                if (!riskEval.isPermitted) {
                                    success = false
                                    errorMsg = "Blocked by RiskEvaluator: ${riskEval.reasoning}"
                                    break
                                }
                                lastOutput = tool.execute(step.parameters)
                            } else {
                                success = false
                                errorMsg = "Tool ${step.toolName} not found"
                                break
                            }
                        } else if (agent != null) {
                            lastOutput = agent.process(event.data.values.firstOrNull() ?: "Event triggered")
                        }
                    } catch (e: Exception) {
                        success = false
                        errorMsg = e.message
                        logger.e("WorkflowEngine", "Error executing step ${step.stepNumber} of workflow ${workflow.name}", e)
                        break
                    }
                }

                results.add(
                    WorkflowExecutionResult(
                        workflowId = workflow.id,
                        workflowName = workflow.name,
                        isSuccess = success,
                        output = lastOutput,
                        error = errorMsg
                    )
                )
            }
        }
        return results
    }
}
