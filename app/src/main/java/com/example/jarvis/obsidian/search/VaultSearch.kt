package com.example.jarvis.obsidian.search

interface VaultSearch {
    suspend fun searchNotes(query: String): List<String>
}
