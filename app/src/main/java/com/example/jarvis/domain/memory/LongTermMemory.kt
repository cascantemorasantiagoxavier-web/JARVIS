package com.example.jarvis.domain.memory

interface LongTermMemory {
    suspend fun store(key: String, value: String)
    suspend fun retrieve(key: String): String?
}
