package com.example.jarvis.obsidian.vault

interface VaultProvider {
    suspend fun selectVault(): Boolean
    suspend fun validateVault(): Boolean
    val vaultPath: String?
    suspend fun listFiles(): List<String>
    suspend fun search(query: String): List<String>
    suspend fun fileExists(path: String): Boolean
    suspend fun deleteFile(path: String): Boolean
}
