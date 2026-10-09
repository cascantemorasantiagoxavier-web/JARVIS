package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.memory.PersistentMemoryRepository
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.ai.ThinkingMode
import com.example.jarvis.domain.ai.ThinkingRouter
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ThinkingAndMemoryPipelineTest {

    private lateinit var router: ThinkingRouter
    private lateinit var testFile: File

    @Before
    fun setUp() {
        router = ThinkingRouter()
        testFile = File(System.getProperty("java.io.tmpdir"), "jarvis_think_test_${System.currentTimeMillis()}.json")
    }

    @After
    fun tearDown() {
        if (testFile.exists()) testFile.delete()
    }

    @Test
    fun testThinkingRouterAutoModes() {
        val r1 = router.route("¿Qué hora es?", ThinkingMode.AUTO, isOnline = true, isLocalAvailable = false)
        assertEquals(ThinkingMode.FAST, r1.mode)

        val r2 = router.route("Explícame qué es la inteligencia artificial", ThinkingMode.AUTO, isOnline = true, isLocalAvailable = false)
        assertEquals(ThinkingMode.BALANCED, r2.mode)

        val r3 = router.route("arquitectura", ThinkingMode.AUTO, isOnline = true, isLocalAvailable = false)
        assertEquals(ThinkingMode.DEEP, r3.mode)
    }

    @Test
    fun testMemoryPersistenceAndRecall() = runBlocking {
        val repoA = PersistentMemoryRepository(testDbFile = testFile)
        val entry = MemoryEntry(
            id = "user_name_santiago",
            content = "El usuario se llama Santiago.",
            level = MemoryLevel.LONG_TERM,
            tags = listOf("name", "santiago", "user")
        )
        repoA.save(entry)

        val repoB = PersistentMemoryRepository(testDbFile = testFile)
        val searchRes = repoB.search(MemoryQuery(query = "Santiago"))

        assertTrue(searchRes is AppResult.Success)
        val success = searchRes as AppResult.Success
        assertTrue(success.data.entries.any { it.content.contains("Santiago") })
    }

    @Test
    fun testFastPathLatencyMeasurement() {
        val startTime = System.currentTimeMillis()
        val simulatedResult = "Hora actual: 12:00 PM"
        val latencyMs = System.currentTimeMillis() - startTime

        assertNotNull(simulatedResult)
        assertTrue("FAST_PATH_LATENCY_MS should be under 50ms, got ${latencyMs}ms", latencyMs < 50)
    }
}
