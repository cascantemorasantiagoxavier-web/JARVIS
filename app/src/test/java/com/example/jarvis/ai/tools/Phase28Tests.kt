package com.example.jarvis.ai.tools

import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.device.DeviceControlProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Phase28Tests {

    private lateinit var toolRegistry: DefaultToolRegistry
    private lateinit var mapsTool: MapsTool
    private lateinit var openWebTool: OpenWebTool
    private lateinit var deviceControlTool: DeviceControlTool
    private lateinit var researchTool: ResearchTool

    @Before
    fun setUp() {
        toolRegistry = DefaultToolRegistry()
        mapsTool = MapsTool()
        openWebTool = OpenWebTool()
        deviceControlTool = DeviceControlTool(providerProvider = {
            object : DeviceControlProvider {
                override val isServiceEnabled = true
                override fun performActionGlobal(action: Int) = true
                override fun clickNodeByText(text: String) = true
                override fun inputText(text: String) = true
                override fun scrollForward() = true
                override fun getActivePackageName() = "com.whatsapp"
            }
        })
        researchTool = ResearchTool()
    }

    @Test
    fun test1_ToolRegistryRegistration() {
        assertNotNull(toolRegistry.getTool("maps_tool"))
        assertNotNull(toolRegistry.getTool("open_web"))
        assertNotNull(toolRegistry.getTool("device_control"))
        assertNotNull(toolRegistry.getTool("research_topic"))
    }

    @Test
    fun test2_MapsToolSearch() = runBlocking {
        val result = mapsTool.execute(mapOf("action" to "search", "query" to "Hospital Mexico"))
        assertTrue(result.contains("Hospital"))
    }

    @Test
    fun test3_MapsToolDirections() = runBlocking {
        val result = mapsTool.execute(mapOf("action" to "directions", "query" to "Aeropuerto", "origin" to "Casa"))
        assertTrue(result.contains("Aeropuerto"))
    }

    @Test
    fun test4_OpenWebToolValidUrl() = runBlocking {
        val result = openWebTool.execute(mapOf("url" to "https://google.com"))
        assertTrue(result.contains("google.com"))
    }

    @Test
    fun test5_OpenWebToolDangerousUrlBlocked() = runBlocking {
        val result = openWebTool.execute(mapOf("url" to "javascript:alert(1)"))
        assertTrue(result.contains("no permitida"))
    }

    @Test
    fun test6_DeviceControlHomeAction() = runBlocking {
        val result = deviceControlTool.execute(mapOf("action" to "home"))
        assertTrue(result.contains("Inicio"))
    }

    @Test
    fun test7_DeviceControlInputText() = runBlocking {
        val result = deviceControlTool.execute(mapOf("action" to "input_text", "text" to "Hola JARVIS"))
        assertTrue(result.contains("Texto escrito"))
    }

    @Test
    fun test8_ResearchToolInvestigation() = runBlocking {
        val result = researchTool.execute(mapOf("query" to "Costa Rica news"))
        assertTrue(result.contains("Investigación sobre"))
    }
}
