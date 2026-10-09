package com.example.jarvis.obsidian.writer

interface VaultWriter {
    suspend fun writeNote(path: String, content: String)
}
