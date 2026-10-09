package com.example.jarvis.ai.automation

import android.content.Context
import androidx.work.*
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import java.util.concurrent.TimeUnit

class ScheduledAutomationRunner(
    private val context: Context,
    private val logger: AppLogger = ConsoleLogger()
) {
    fun schedulePeriodicAutomation(workflowId: String, intervalMinutes: Long = 15, cronExpression: String = "0 * * * *") {
        val slotTimestamp = System.currentTimeMillis() / (1000 * 60 * 15)
        val inputData = Data.Builder()
            .putString("workflowId", workflowId)
            .putString("cronExpression", cronExpression)
            .putString("correlationId", "cron_${workflowId}_$slotTimestamp")
            .putLong("slotTimestamp", slotTimestamp)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<AutomationWorker>(
            intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES
        )
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag("cron_workflow_$workflowId")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "cron_automation_$workflowId",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        logger.i("ScheduledAutomationRunner", "Enqueued periodic WorkManager schedule for workflow $workflowId (Cron: $cronExpression)")
    }

    fun cancelScheduledAutomation(workflowId: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("cron_automation_$workflowId")
        workManager.cancelAllWorkByTag("cron_workflow_$workflowId")
        logger.i("ScheduledAutomationRunner", "Cancelled scheduled WorkManager work and tags for workflow $workflowId")
    }
}
