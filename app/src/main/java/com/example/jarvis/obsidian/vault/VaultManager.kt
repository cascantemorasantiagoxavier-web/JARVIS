package com.example.jarvis.obsidian.vault

interface VaultManager {
    suspend fun syncVault(): Boolean
    suspend fun readFile(path: String): String?
    suspend fun writeFile(path: String, content: String)
    suspend fun listFiles(): List<String>
    suspend fun search(query: String): List<String>
    suspend fun fileExists(path: String): Boolean
    suspend fun deleteFile(path: String): Boolean
}
