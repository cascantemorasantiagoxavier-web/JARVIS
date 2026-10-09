package com.example.jarvis.memory

import com.example.jarvis.ai.memory.DefaultKnowledgeRetriever
import com.example.jarvis.ai.memory.DefaultMemoryPolicy
import com.example.jarvis.ai.memory.DefaultMemoryRepository
import com.example.jarvis.core.common.AppResult
import com.example.jarvis.domain.memory.models.*
import com.example.jarvis.obsidian.vault.MockVaultManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MemoryPhase5Test {

    private lateinit var repository: DefaultMemoryRepository
    private lateinit var policy: DefaultMemoryPolicy
    private lateinit var mockVault: MockVaultManager
    private lateinit var knowledgeRetriever: DefaultKnowledgeRetriever

    @Before
    fun setUp() {
        repository = DefaultMemoryRepository()
        policy = DefaultMemoryPolicy()
        mockVault = MockVaultManager()
        knowledgeRetriever = DefaultKnowledgeRetriever(mockVault)
    }

    @Test
    fun testMemoryRepositorySaveAndGet() = runBlocking {
        val entry = MemoryEntry(content = "User prefers dark theme", level = MemoryLevel.LONG_TERM, importance = 0.9f)
        val saveResult = repository.save(entry)

        assertTrue(saveResult is AppResult.Success)
        val savedEntry = (saveResult as AppResult.Success).data
        assertEquals("User prefers dark theme", savedEntry.content)

        val getResult = repository.get(entry.id)
        assertTrue(getResult is AppResult.Success)
        assertEquals(entry.id, (getResult as AppResult.Success).data?.id)
    }

    @Test
    fun testMemoryRepositoryUpdateAndDelete() = runBlocking {
        val entry = MemoryEntry(content = "Initial preference", level = MemoryLevel.LONG_TERM)
        repository.save(entry)

        val updatedEntry = entry.copy(content = "Updated preference")
        val updateResult = repository.update(updatedEntry)
        assertTrue(updateResult is AppResult.Success)

        val getResult = repository.get(entry.id)
        assertEquals("Updated preference", (getResult as AppResult.Success).data?.content)

        val deleteResult = repository.delete(entry.id)
        assertTrue(deleteResult is AppResult.Success)
        assertTrue((deleteResult as AppResult.Success).data)

        val getAfterDelete = repository.get(entry.id)
        assertNull((getAfterDelete as AppResult.Success).data)
    }

    @Test
    fun testMemoryRepositorySearchAndQuery() = runBlocking {
        repository.save(MemoryEntry(content = "User name is Carlos", level = MemoryLevel.LONG_TERM, importance = 0.8f))
        repository.save(MemoryEntry(content = "User lives in Madrid", level = MemoryLevel.LONG_TERM, importance = 0.7f))
        repository.save(MemoryEntry(content = "Temporary session note", level = MemoryLevel.WORKING, importance = 0.1f))

        val searchResult = repository.search(
            MemoryQuery(query = "Carlos", targetLevels = listOf(MemoryLevel.LONG_TERM))
        )
        assertTrue(searchResult is AppResult.Success)
        val result = (searchResult as AppResult.Success).data
        assertEquals(1, result.entries.size)
        assertEquals("User name is Carlos", result.entries[0].content)
    }

    @Test
    fun testMemoryPolicyIgnoresTrivialInput() = runBlocking {
        val decision = policy.evaluate("hola")
        assertEquals(MemoryPolicyDecision.IGNORE, decision.decision)
    }

    @Test
    fun testMemoryPolicyDeduplication() = runBlocking {
        val existing = listOf(
            MemoryEntry(content = "User prefers Kotlin", level = MemoryLevel.LONG_TERM)
        )
        val decision = policy.evaluate("User prefers Kotlin", MemoryLevel.LONG_TERM, existingEntries = existing)
        assertEquals(MemoryPolicyDecision.IGNORE, decision.decision)
        assertTrue(decision.reasoning.contains("duplicate", ignoreCase = true))
    }

    @Test
    fun testMemoryPolicyUpdateTopic() = runBlocking {
        val existing = listOf(
            MemoryEntry(id = "entry_1", content = "Mi nombre es Juan", level = MemoryLevel.LONG_TERM)
        )
        val decision = policy.evaluate("Mi nombre es Carlos", MemoryLevel.LONG_TERM, existingEntries = existing)
        assertEquals(MemoryPolicyDecision.UPDATE, decision.decision)
        assertEquals("entry_1", decision.existingEntryId)
    }

    @Test
    fun testMemoryPolicyDestructiveOperationRequiresConfirmation() = runBlocking {
        val decision = policy.evaluate("DELETE: user_profile")
        assertEquals(MemoryPolicyDecision.DELETE_REVIEW, decision.decision)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun testKnowledgeRetrieverSelectiveSnippets() = runBlocking {
        mockVault.writeFile("notes/architecture.md", "# Architecture\nJARVIS usa Clean Architecture y Obsidian Vault")
        val retrieveResult = knowledgeRetriever.retrieveKnowledge("Clean Architecture")

        assertTrue(retrieveResult is AppResult.Success)
        val result = (retrieveResult as AppResult.Success).data
        assertTrue(result.entries.isNotEmpty())
        assertEquals(MemoryLevel.KNOWLEDGE, result.entries[0].level)
        assertTrue(result.snippets.any { it.contains("Clean Architecture") })
    }
}
