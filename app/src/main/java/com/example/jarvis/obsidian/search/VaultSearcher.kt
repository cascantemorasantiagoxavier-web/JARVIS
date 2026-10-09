package com.example.jarvis.obsidian.search

/**
 * Data class representing a matching snippet inside a vault note.
 */
data class VaultSnippet(
    val filePath: String,
    val lineContent: String,
    val lineNumber: Int
)

/**
 * Enhanced searcher interface for Obsidian Vault.
 */
interface VaultSearcher {
    suspend fun searchFiles(query: String): List<String>
    suspend fun searchSnippets(query: String, maxResults: Int = 10): List<VaultSnippet>
}
