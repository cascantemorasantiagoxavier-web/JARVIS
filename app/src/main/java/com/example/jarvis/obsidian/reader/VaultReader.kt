package com.example.jarvis.obsidian.reader

interface VaultReader {
    suspend fun readNote(path: String): String?
}
