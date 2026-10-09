package com.example.jarvis.obsidian.vault

import com.example.jarvis.obsidian.reader.VaultReader
import com.example.jarvis.obsidian.writer.VaultWriter

class VaultManagerImpl(private val provider: VaultProvider) : VaultManager {
    override suspend fun syncVault(): Boolean {
        return provider.validateVault()
    }

    override suspend fun readFile(path: String): String? {
        return (provider as? VaultReader)?.readNote(path)
    }

    override suspend fun writeFile(path: String, content: String) {
        (provider as? VaultWriter)?.writeNote(path, content)
    }

    override suspend fun listFiles(): List<String> {
        return provider.listFiles()
    }

    override suspend fun search(query: String): List<String> {
        return provider.search(query)
    }

    override suspend fun fileExists(path: String): Boolean {
        return provider.fileExists(path)
    }

    override suspend fun deleteFile(path: String): Boolean {
        return provider.deleteFile(path)
    }
}
