package com.example.jarvis.ai.context

interface ContextBuilder {
    suspend fun buildContext(request: ContextRequest): Map<String, Any>
}
