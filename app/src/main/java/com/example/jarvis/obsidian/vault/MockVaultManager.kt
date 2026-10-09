package com.example.jarvis.obsidian.vault

import com.example.jarvis.obsidian.reader.VaultReader
import com.example.jarvis.obsidian.search.VaultSearch
import com.example.jarvis.obsidian.search.VaultSearcher
import com.example.jarvis.obsidian.search.VaultSnippet
import com.example.jarvis.obsidian.writer.VaultWriter

  class MockVaultManager : VaultManager, VaultProvider, VaultReader, VaultWriter, VaultSearch, VaultSearcher {
    private val mockVault = mutableMapOf("index.md" to "Bienvenido a la abstracción del Vault de Obsidian de JARVIS")

    override suspend fun syncVault(): Boolean = true

    override suspend fun readFile(path: String): String? = mockVault[path]

    override suspend fun writeFile(path: String, content: String) {
        mockVault[path] = content
    }

    override suspend fun listFiles(): List<String> = mockVault.keys.toList()
    override suspend fun search(query: String): List<String> = searchNotes(query)

    override suspend fun fileExists(path: String): Boolean = mockVault.containsKey(path)

    override suspend fun deleteFile(path: String): Boolean = mockVault.remove(path) != null

    override suspend fun selectVault(): Boolean = true

    override suspend fun validateVault(): Boolean = true

    override val vaultPath: String? = "/mock/obsidian/vault"

    override suspend fun readNote(path: String): String? = readFile(path)

    override suspend fun writeNote(path: String, content: String) {
        writeFile(path, content)
    }

    override suspend fun searchNotes(query: String): List<String> {
        return mockVault.entries
            .filter { it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
            .map { it.key }
    }

    override suspend fun searchFiles(query: String): List<String> = searchNotes(query)

    override suspend fun searchSnippets(query: String, maxResults: Int): List<VaultSnippet> {
        val snippets = mutableListOf<VaultSnippet>()
        for ((file, content) in mockVault) {
            val lines = content.lines()
            lines.forEachIndexed { index, line ->
                if (line.contains(query, ignoreCase = true)) {
                    snippets.add(VaultSnippet(filePath = file, lineContent = line, lineNumber = index + 1))
                    if (snippets.size >= maxResults) return snippets
                }
            }
        }
        return snippets
    }
}
