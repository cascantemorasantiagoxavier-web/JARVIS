package com.example.jarvis.ai.agents

import com.example.jarvis.domain.tools.Tool

interface ToolRegistry {
    fun registerTool(tool: Tool)
    fun getTool(name: String): Tool?

    fun getRegisteredTools(): List<Tool> = emptyList()
}
