package com.example.jarvis.domain.memory

interface SessionMemory {
    fun getSessionHistory(): List<String>
    fun addSessionEntry(entry: String)
}
