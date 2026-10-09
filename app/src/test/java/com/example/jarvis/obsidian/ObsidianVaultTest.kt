package com.example.jarvis.obsidian

import com.example.jarvis.obsidian.vault.MockVaultManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObsidianVaultTest {

    @Test
    fun testVaultSelectionAndValidationMock() = runBlocking {
        val vaultManager = MockVaultManager()
        val selected = vaultManager.selectVault()
        assertTrue("Vault selection should succeed", selected)

        val valid = vaultManager.validateVault()
        assertTrue("Vault validation should succeed", valid)

        val path = vaultManager.vaultPath
        assertNotNull("Vault path should not be null", path)
    }

    @Test
    fun testVaultReadWriteOperations() = runBlocking {
        val vaultManager = MockVaultManager()
        val testPath = "test_note.md"
        val testContent = "# Test Note Content"

        vaultManager.writeFile(testPath, testContent)
        val readContent = vaultManager.readFile(testPath)

        assertEquals("Written and read content should match", testContent, readContent)

        val files = vaultManager.listFiles()
        assertTrue("File list should contain test note", files.contains(testPath))

        val searchResults = vaultManager.search("Test Note")
        assertTrue("Search should find test note", searchResults.contains(testPath))
    }
}
