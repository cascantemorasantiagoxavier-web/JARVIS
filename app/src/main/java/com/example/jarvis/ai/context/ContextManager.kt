package com.example.jarvis.ai.context

interface ContextManager {
    fun getCurrentContext(): Map<String, String>
    fun updateContext(key: String, value: String)
}
