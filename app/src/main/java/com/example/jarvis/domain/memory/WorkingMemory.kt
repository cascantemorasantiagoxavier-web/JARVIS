package com.example.jarvis.domain.memory

interface WorkingMemory {
    fun getActiveContext(): Map<String, Any>
    fun updateContext(key: String, value: Any)
}
