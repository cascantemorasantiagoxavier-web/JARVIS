package com.example.jarvis.core.events

import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.AuditLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.core.logging.DefaultAuditLogger
import com.example.jarvis.ai.orchestrator.models.AuditLogEntry
import com.example.jarvis.domain.events.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

class RobustEventBus(
    private val auditLogger: AuditLogger = DefaultAuditLogger(),
    private val logger: AppLogger = ConsoleLogger()
) : EventBus {

    private val subscriptions = ConcurrentHashMap<String, EventSubscription>()
    private val processedEventFingerprints = mutableSetOf<String>()
    private val visitedEventChains = ConcurrentHashMap<String, Int>()

    private val _eventsFlow = MutableSharedFlow<AppEvent>(replay = 0, extraBufferCapacity = 64)
    val eventsFlow: Flow<AppEvent> = _eventsFlow.asSharedFlow()

    override suspend fun publish(event: AppEvent) {
        // 1. Idempotency check
        val fingerprint = "${event.type}_${event.correlationId}_${event.data.hashCode()}"
        if (processedEventFingerprints.contains(fingerprint)) {
            logger.i("RobustEventBus", "Duplicate event suppressed by idempotency check: ${event.id}")
            return
        }
        processedEventFingerprints.add(fingerprint)

        // 2. Loop protection check
        val currentDepth = visitedEventChains.getOrPut(event.correlationId) { 0 }
        if (currentDepth > 5) {
            logger.e("RobustEventBus", "Loop protection triggered for correlationId: ${event.correlationId} at depth $currentDepth")
            return
        }
        visitedEventChains[event.correlationId] = currentDepth + 1

        _eventsFlow.emit(event)

        // 3. Dispatch to subscribers with error isolation and priority sorting
        val matchingSubs = subscriptions.values.filter { sub ->
            sub.eventType == null || sub.eventType == event.type
        }.sortedByDescending { it.priority }

        for (sub in matchingSubs) {
            try {
                sub.handler(event)
                auditLogger.logAction(
                    AuditLogEntry(
                        requestId = event.id,
                        intent = "EVENT_HANDLER_${sub.handlerName}",
                        resultSummary = "Successfully handled event ${event.type}",
                        isSuccess = true
                    )
                )
            } catch (e: Exception) {
                logger.e("RobustEventBus", "Handler '${sub.handlerName}' failed for event ${event.id}", e)
                auditLogger.logAction(
                    AuditLogEntry(
                        requestId = event.id,
                        intent = "EVENT_HANDLER_${sub.handlerName}",
                        resultSummary = "Error handling event ${event.type}: ${e.message}",
                        isSuccess = false
                    )
                )
                // Error isolation: continue with other handlers
            }
        }
    }

    fun subscribe(subscription: EventSubscription): String {
        subscriptions[subscription.id] = subscription
        return subscription.id
    }

    fun unsubscribe(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
    }

    override fun subscribe(eventType: EventType?): Flow<AppEvent> {
        return eventsFlow
    }
}
