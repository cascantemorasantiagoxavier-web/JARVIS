package com.example.jarvis.core.logging

import com.example.jarvis.ai.orchestrator.models.AuditLogEntry

interface AuditLogger {
    fun logAction(entry: AuditLogEntry)
    fun getLogEntries(): List<AuditLogEntry>
}

class DefaultAuditLogger(
    private val appLogger: AppLogger? = ConsoleLogger()
) : AuditLogger {

    private val entries = mutableListOf<AuditLogEntry>()

    override fun logAction(entry: AuditLogEntry) {
        val sanitizedMetadata = sanitizeMap(entry.inputMetadata)
        val sanitizedEntry = entry.copy(inputMetadata = sanitizedMetadata)
        synchronized(entries) {
            entries.add(sanitizedEntry)
        }
        appLogger?.i("AuditLogger", "AUDIT [${sanitizedEntry.requestId}] ${sanitizedEntry.intent} -> Agent:${sanitizedEntry.agentId ?: "none"} Tool:${sanitizedEntry.toolName ?: "none"} (Success:${sanitizedEntry.isSuccess})")
    }

    override fun getLogEntries(): List<AuditLogEntry> {
        return synchronized(entries) { entries.toList() }
    }

    private fun sanitizeMap(map: Map<String, String>): Map<String, String> {
        val sensitiveKeys = listOf("key", "token", "password", "secret", "auth", "credential", "bearer", "header")
        return map.mapValues { (k, v) ->
            if (sensitiveKeys.any { k.lowercase().contains(it) }) {
                "[REDACTED]"
            } else {
                v
            }
        }
    }
}
