# Automation Engine & WorkManager Scheduler

The `AutomationEngine` executes declarative event-driven and scheduled workflows, evaluating triggers and deterministic conditions before invoking tools and agents safely through `RiskEvaluator` and `AuditLogger`.

---

## Capabilities & WorkManager Background Execution
- **`AutomationWorker` & `ScheduledAutomationRunner`**: Connects WorkManager periodic triggers (`schedulePeriodicAutomation`) to `AutomationEngine` for background execution without requiring the UI to be open.
- **Real CRON Occurrence Calculation (`CronOccurrenceCalculator`)**: Distinguishes real cron schedule occurrences (e.g., `0 8 * * *`, `*/30 * * * *`, weekly/monthly patterns) from naive 15-minute polling windows. When WorkManager wakes up late (e.g. at 08:07 or 08:16 for a 08:00 schedule), it calculates the exact scheduled occurrence timestamp (`08:00`), ensuring stable, predictable occurrence identity across varying execution delays.
- **Timing of `markExecuted()`**: To prevent partial or crashed runs from being permanently marked as processed, `markExecuted()` is invoked **ONLY after successful execution** of an occurrence. If an execution fails or crashes, it remains unmarked for safe retry logic.
- **Persistent Idempotency**: Tracked via `AutomationOccurrenceManager` (backed by `SharedPreferences`) to survive process death and device reboots.
- **Missed Schedules & Catch-up Policy (`maxCatchUp = 3`)**: Safely catches up unexecuted past cron occurrences within a deterministic lookback window, ensuring all actions pass through `RiskEvaluator`, `ConfirmationToken`, and deterministic conditions.
- **Cancellation**: `cancelScheduledAutomation` cleanly cancels unique work (`cron_automation_$workflowId`) and tags (`cron_workflow_$workflowId`).
- **Battery & Constraints**: Respects `BatteryNotLow` constraints (`setRequiresBatteryNotLow(true)`), resuming scheduled occurrences once power conditions are met.
- **Proactive Notifications**: `ProactiveNotificationManager` posts native notifications for `HIGH` and `URGENT` suggestions with deduplication and immutable `PendingIntent` security.
