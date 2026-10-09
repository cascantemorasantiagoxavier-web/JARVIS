package com.example.jarvis.ai.automation

import java.util.Calendar
import java.util.TimeZone

object CronOccurrenceCalculator {

    data class ScheduledOccurrence(
        val timestamp: Long,
        val occurrenceKey: String,
        val slotDescription: String
    )

    fun calculatePendingOccurrences(
        workflowId: String,
        cronExpression: String?,
        currentTime: Long = System.currentTimeMillis(),
        lookbackCount: Int = 3
    ): List<ScheduledOccurrence> {
        val occurrences = mutableListOf<ScheduledOccurrence>()
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = currentTime }

        val expr = (cronExpression ?: "0 * * * *").trim()
        val parts = expr.split("\\s+".toRegex())

        val minuteStr = parts.getOrNull(0) ?: "0"
        val hourStr = parts.getOrNull(1) ?: "*"
        val dayMonthStr = parts.getOrNull(2) ?: "*"
        val dayWeekStr = parts.getOrNull(4) ?: "*"

        val isHourlyInterval = minuteStr.startsWith("*/")
        val intervalMinutes = if (isHourlyInterval) {
            minuteStr.removePrefix("*/").toLongOrNull() ?: 30L
        } else {
            null
        }

        val targetHour = if (!hourStr.contains("*") && !hourStr.contains("/")) {
            hourStr.toIntOrNull() ?: 0
        } else {
            null
        }

        val targetMinute = if (!minuteStr.contains("*") && !minuteStr.contains("/") && !minuteStr.contains(",")) {
            minuteStr.toIntOrNull() ?: 0
        } else {
            0
        }

        val stepCount = lookbackCount.coerceIn(1, 5)
        for (i in (stepCount - 1) downTo 0) {
            val testCal = (cal.clone() as Calendar)

            when {
                intervalMinutes != null -> {
                    val currentMin = testCal.get(Calendar.MINUTE)
                    val flooredMin = (currentMin / intervalMinutes) * intervalMinutes
                    testCal.set(Calendar.MINUTE, flooredMin.toInt())
                    testCal.set(Calendar.SECOND, 0)
                    testCal.set(Calendar.MILLISECOND, 0)
                    testCal.add(Calendar.MINUTE, (-i * intervalMinutes).toInt())
                }
                targetHour != null && dayMonthStr == "*" && dayWeekStr == "*" -> {
                    testCal.set(Calendar.HOUR_OF_DAY, targetHour)
                    testCal.set(Calendar.MINUTE, targetMinute)
                    testCal.set(Calendar.SECOND, 0)
                    testCal.set(Calendar.MILLISECOND, 0)
                    testCal.add(Calendar.DAY_OF_YEAR, -i)
                    if (testCal.timeInMillis > currentTime + 60000 && i == 0) {
                        testCal.add(Calendar.DAY_OF_YEAR, -1)
                    }
                }
                targetHour != null && dayWeekStr != "*" -> {
                    val targetDayOfWeek = dayWeekStr.toIntOrNull() ?: Calendar.MONDAY
                    testCal.set(Calendar.HOUR_OF_DAY, targetHour)
                    testCal.set(Calendar.MINUTE, targetMinute)
                    testCal.set(Calendar.SECOND, 0)
                    testCal.set(Calendar.MILLISECOND, 0)
                    testCal.add(Calendar.WEEK_OF_YEAR, -i)
                    while (testCal.get(Calendar.DAY_OF_WEEK) != targetDayOfWeek) {
                        testCal.add(Calendar.DAY_OF_MONTH, -1)
                    }
                }
                targetHour != null && dayMonthStr != "*" -> {
                    val targetDay = dayMonthStr.toIntOrNull() ?: 1
                    testCal.set(Calendar.DAY_OF_MONTH, targetDay)
                    testCal.set(Calendar.HOUR_OF_DAY, targetHour)
                    testCal.set(Calendar.MINUTE, targetMinute)
                    testCal.set(Calendar.SECOND, 0)
                    testCal.set(Calendar.MILLISECOND, 0)
                    testCal.add(Calendar.MONTH, -i)
                }
                else -> {
                    testCal.set(Calendar.MINUTE, 0)
                    testCal.set(Calendar.SECOND, 0)
                    testCal.set(Calendar.MILLISECOND, 0)
                    testCal.add(Calendar.HOUR_OF_DAY, -i)
                }
            }

            val scheduledTime = testCal.timeInMillis
            if (scheduledTime <= currentTime + 60000) {
                val occurrenceKey = "cron_${workflowId}_$scheduledTime"
                val slotDesc = testCal.time.toString()
                occurrences.add(ScheduledOccurrence(scheduledTime, occurrenceKey, slotDesc))
            }
        }

        return occurrences.distinctBy { it.occurrenceKey }
    }
}
