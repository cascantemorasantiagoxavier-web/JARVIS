package com.example.jarvis.ai.tools

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.DefaultAiOrchestrator
import com.example.jarvis.core.device.AppManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Phase2LocalCoreTest {

    private lateinit var intentDetector: DefaultIntentDetector
    private lateinit var toolRegistry: DefaultToolRegistry
    private lateinit var dateTimeTool: GetDateTimeTool
    private lateinit var calculatorTool: CalculatorTool
    private lateinit var batteryTool: BatteryTool
    private lateinit var checkNetworkTool: CheckNetworkTool
    private lateinit var orchestrator: DefaultAiOrchestrator

    @Before
    fun setUp() {
        intentDetector = DefaultIntentDetector()
        toolRegistry = DefaultToolRegistry()
        dateTimeTool = GetDateTimeTool()
        calculatorTool = CalculatorTool()
        batteryTool = BatteryTool(appManagerProvider = {
            object : AppManager {
                override fun isAppInstalled(packageName: String) = true
                override fun launchApp(packageName: String) = Result.success(Unit)
                override fun openSettings(settingType: String) = Result.success(Unit)
                override fun getDeviceModel() = "Pixel 8"
                override fun getAndroidVersion() = "Android 15"
                override fun getBatteryLevel() = Result.success(85)
            }
        })
        checkNetworkTool = CheckNetworkTool()

        orchestrator = DefaultAiOrchestrator(
            intentDetector = intentDetector,
            contextManager = DefaultContextManager(),
            memoryManager = DefaultAiMemoryManager(),
            agentRegistry = DefaultAgentRegistry(),
            toolRegistry = toolRegistry
        )
    }

    @Test
    fun testIntentDetectorTimeAndDatePriority() = runBlocking {
        assertEquals("DATE_TIME_QUERY", intentDetector.detectIntent("¿Qué hora es?"))
        assertEquals("DATE_TIME_QUERY", intentDetector.detectIntent("Dime la hora"))
        assertEquals("DATE_TIME_QUERY", intentDetector.detectIntent("¿Qué fecha es hoy?"))
        assertEquals("DATE_TIME_QUERY", intentDetector.detectIntent("Qué día es hoy"))
    }

    @Test
    fun testIntentDetectorMathAndCalculatorPriority() = runBlocking {
        assertEquals("CALCULATOR_QUERY", intentDetector.detectIntent("25 por 40"))
        assertEquals("CALCULATOR_QUERY", intentDetector.detectIntent("100 / 4"))
        assertEquals("CALCULATOR_QUERY", intentDetector.detectIntent("15% de 200"))
        assertEquals("CALCULATOR_QUERY", intentDetector.detectIntent("125 + 75"))
        assertEquals("CALCULATOR_QUERY", intentDetector.detectIntent("1000 - 350"))
    }

    @Test
    fun testIntentDetectorBatteryAndNetworkPriority() = runBlocking {
        assertEquals("BATTERY_QUERY", intentDetector.detectIntent("¿Cuánta batería tengo?"))
        assertEquals("NETWORK_QUERY", intentDetector.detectIntent("¿Tengo Internet?"))
    }

    @Test
    fun testGetDateTimeToolOutputFormat() = runBlocking {
        val timeRes = dateTimeTool.execute(mapOf("query" to "¿Qué hora es?"))
        assertTrue(timeRes.contains("Son las"))

        val dateRes = dateTimeTool.execute(mapOf("query" to "¿Qué fecha es hoy?"))
        assertTrue(dateRes.contains("Hoy es"))
    }

    @Test
    fun testCalculatorToolMathOperations() = runBlocking {
        assertEquals("El resultado de 25.0 × 40.0 es 1000.", calculatorTool.execute(mapOf("query" to "25 por 40")))
        assertEquals("El resultado de 100.0 ÷ 4.0 es 25.", calculatorTool.execute(mapOf("query" to "100 / 4")))
        assertEquals("El 15.0% de 200.0 es 30.", calculatorTool.execute(mapOf("query" to "15% de 200")))
        assertEquals("El resultado de 125.0 + 75.0 es 200.", calculatorTool.execute(mapOf("query" to "125 + 75")))
        assertEquals("El resultado de 1000.0 - 350.0 es 650.", calculatorTool.execute(mapOf("query" to "1000 - 350")))
    }

    @Test
    fun testBatteryToolOutput() = runBlocking {
        val res = batteryTool.execute(emptyMap())
        assertEquals("El nivel de batería actual es del 85%.", res)
    }

    @Test
    fun testCheckNetworkToolOutput() = runBlocking {
        val res = checkNetworkTool.execute(emptyMap())
        assertTrue(res.contains("conexión") || res.contains("Internet"))
    }

    @Test
    fun testOrchestratorLocalTimeQueryOffline() = runBlocking {
        val response = orchestrator.coordinate("¿Qué hora es?")
        assertTrue(response.contains("Son las"))
    }

    @Test
    fun testOrchestratorLocalDateQueryOffline() = runBlocking {
        val response = orchestrator.coordinate("¿Qué fecha es hoy?")
        assertTrue(response.contains("Hoy es"))
    }

    @Test
    fun testOrchestratorLocalCalculatorOffline() = runBlocking {
        val response = orchestrator.coordinate("25 por 40")
        assertTrue(response.contains("1000"))
    }

    @Test
    fun testNonRegressionQueries() = runBlocking {
        assertEquals("CONVERSATION", intentDetector.detectIntent("Hola JARVIS"))
        assertEquals("OPEN_SETTINGS", intentDetector.detectIntent("Abre los ajustes"))
        assertEquals("MAPS", intentDetector.detectIntent("Abre Google Maps"))
        assertEquals("WEB_SEARCH", intentDetector.detectIntent("Busca información sobre Costa Rica"))
    }
}
