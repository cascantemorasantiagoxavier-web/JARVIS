package com.example.jarvis.ai.memory

import com.example.jarvis.domain.memory.models.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Memory2Tests {

    private lateinit var policy: Memory2Policy

    @Before
    fun setUp() {
        policy = Memory2Policy()
    }

    @Test
    fun testMemoryWritePolicyIgnoresTrivial() {
        val record = MemoryRecord(content = "Estoy cansado hoy")
        val decision = policy.evaluate(record, emptyList())
        assertEquals(MemoryDecisionAction.SHOULD_IGNORE, decision.action)
    }

    @Test
    fun testMemoryDeduplication() {
        val existing = listOf(
            MemoryRecord(id = "1", content = "Prefiero respuestas cortas")
        )
        val candidate = MemoryRecord(content = "Prefiero respuestas cortas")
        val decision = policy.evaluate(candidate, existing)
        assertEquals(MemoryDecisionAction.SHOULD_UPDATE, decision.action)
        assertEquals("1", decision.existingRecordId)
    }

    @Test
    fun testMemoryContradiction() {
        val existing = listOf(
            MemoryRecord(id = "1", content = "El usuario prefiere el tema claro", confidence = MemoryConfidence.HIGH)
        )
        val candidate = MemoryRecord(content = "El usuario prefiere el tema oscuro", confidence = MemoryConfidence.LOW)
        val decision = policy.evaluate(candidate, existing)
        assertEquals(MemoryDecisionAction.REQUIRES_REVIEW, decision.action)
    }

    @Test
    fun testMemoryExpiration() {
        val expiredRecord = MemoryRecord(
            content = "Trabajando en X esta semana",
            expiration = System.currentTimeMillis() - 1000
        )
        val decision = policy.evaluate(expiredRecord, emptyList())
        assertEquals(MemoryDecisionAction.SHOULD_IGNORE, decision.action)
    }

    @Test
    fun testMemoryScoringAndImportance() {
        val criticalRec = MemoryRecord(content = "Critical fact", importance = MemoryImportance.CRITICAL)
        val lowRec = MemoryRecord(content = "Low fact", importance = MemoryImportance.LOW)
        assertTrue(criticalRec.importance.weight > lowRec.importance.weight)
    }

    @Test
    fun testMemoryValidStorage() {
        val candidate = MemoryRecord(content = "Prefiero Kotlin para desarrollo Android")
        val decision = policy.evaluate(candidate, emptyList())
        assertEquals(MemoryDecisionAction.SHOULD_STORE, decision.action)
        assertNotNull(decision.record)
    }
}
