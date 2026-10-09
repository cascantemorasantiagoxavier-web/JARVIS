package com.example.jarvis.ai.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventSource
import com.example.jarvis.domain.events.EventType

class AutomationWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val automationEngine = AutomationEngine()
    private val occurrenceManager = AutomationOccurrenceManager(context)
    private val auditLogger: AuditLogger = DefaultAuditLogger()
    private val logger: AppLogger = ConsoleLogger()

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString("workflowId") ?: return Result.failure()
        val cronExpression = inputData.getString("cronExpression") ?: "0 * * * *"
        val causationId = inputData.getString("causationId")

        logger.i("AutomationWorker", "Starting background AutomationWorker execution for workflow $workflowId with cron '$cronExpression'")

        return try {
            val pendingOccurrences = CronOccurrenceCalculator.calculatePendingOccurrences(
                workflowId = workflowId,
                cronExpression = cronExpression,
                currentTime = System.currentTimeMillis(),
                lookbackCount = 3
            )

            var allSuccess = true
            var executedCount = 0
            val maxCatchUp = 3

            for (occurrence in pendingOccurrences) {
                if (executedCount >= maxCatchUp) break

                if (occurrenceManager.isExecuted(occurrence.occurrenceKey)) {
                    logger.i("AutomationWorker", "Occurrence ${occurrence.occurrenceKey} already executed. Skipping (idempotency enforced).")
                    continue
                }

                val event = AppEvent(
                    type = EventType.ROUTINE_TRIGGERED,
                    source = EventSource.SYSTEM,
                    correlationId = occurrence.occurrenceKey,
                    causationId = causationId,
                    data = mapOf(
                        "workflowId" to workflowId,
                        "slotTimestamp" to occurrence.timestamp.toString(),
                        "scheduledOccurrenceTimestamp" to occurrence.timestamp.toString()
                    )
                )

                val executions = automationEngine.handleEvent(event, forceManual = true)
                
                // CRITICAL: markExecuted is called ONLY AFTER successful execution of the occurrence
                val isSuccess = executions.all { it.status == AutomationExecutionStatus.SUCCESS }
                if (isSuccess || executions.isEmpty()) {
                    occurrenceManager.markExecuted(occurrence.occurrenceKey)
                    executedCount++
                } else {
                    allSuccess = false
                }

                auditLogger.logAction(
                    AuditLogEntry(
                        requestId = occurrence.occurrenceKey,
                        intent = "WORKMANAGER_AUTOMATION_EXECUTION",
                        resultSummary = "Background execution for occurrence ${occurrence.slotDescription} completed with ${executions.size} results",
                        isSuccess = isSuccess
                    )
                )
            }

            if (!allSuccess) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            logger.e("AutomationWorker", "Error in AutomationWorker", e)
            auditLogger.logAction(
                AuditLogEntry(
                    requestId = workflowId,
                    intent = "WORKMANAGER_AUTOMATION_EXECUTION",
                    resultSummary = "Failed execution: ${e.message}",
                    isSuccess = false
                )
            )
            Result.failure()
        }
    }
}
