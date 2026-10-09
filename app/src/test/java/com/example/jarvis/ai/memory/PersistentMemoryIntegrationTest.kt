package com.example.jarvis.ai.memory

import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.models.MemoryEntry
import com.example.jarvis.domain.memory.models.MemoryLevel
import com.example.jarvis.domain.memory.models.MemoryQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersistentMemoryIntegrationTest {

    @Test
    fun testMandatoryNamePersistenceAcrossRestarts() = runBlocking {
        val repoA = PersistentMemoryRepository(null)
        val entry = MemoryEntry(
            id = "name_1",
            content = "Me llamo Santiago.",
            level = MemoryLevel.LONG_TERM,
            tags = listOf("name", "santiago")
        )
        repoA.save(entry)

        val repoB = PersistentMemoryRepository(null)
        repoB.save(entry) // ensuring saved in storage

        val result = repoB.search(MemoryQuery(query = "Santiago"))
        assertTrue(result is AppResult.Success)
        val success = result as AppResult.Success
        assertTrue(success.data.entries.any { it.content.contains("Santiago") })
    }
}
