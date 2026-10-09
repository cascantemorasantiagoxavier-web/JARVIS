package com.example.jarvis.ai.memory

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class MemoryAndBrainIntegrationTest {

    private lateinit var testFile: File

    @Before
    fun setUp() {
        testFile = File(System.getProperty("java.io.tmpdir"), "jarvis_mem_test_${System.currentTimeMillis()}.json")
    }

    @After
    fun tearDown() {
        if (testFile.exists()) testFile.delete()
    }

    @Test
    fun testIdentityMemoryRecallChainWithoutResaving() = runBlocking {
        val repoA = PersistentMemoryRepository(testDbFile = testFile)
        val entry = MemoryEntry(
            id = "name_1",
            content = "Me llamo Santiago.",
            level = MemoryLevel.LONG_TERM,
            tags = listOf("name", "santiago")
        )
        repoA.save(entry)

        val repoB = PersistentMemoryRepository(testDbFile = testFile)
        val result = repoB.search(MemoryQuery(query = "Santiago"))

        assertTrue(result is AppResult.Success)
        val success = result as AppResult.Success
        assertTrue(success.data.entries.any { it.content.contains("Santiago") })
    }
}
