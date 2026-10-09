package com.example.jarvis.ai.research

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.DefaultAiOrchestrator
import com.example.jarvis.ai.tools.GetDateTimeTool
import com.example.jarvis.ai.tools.ResearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Phase3WebSearchTest {

    private lateinit var intentDetector: DefaultIntentDetector
    private lateinit var mockClient: MockWebSearchClient
    private lateinit var mockResearchEngine: ResearchEngine
    private lateinit var mockResearchTool: ResearchTool
    private lateinit var orchestrator: DefaultAiOrchestrator

    @Before
    fun setUp() {
        intentDetector = DefaultIntentDetector()
        mockClient = MockWebSearchClient()
        mockResearchEngine = ResearchEngine(searchClient = mockClient)
        mockResearchTool = ResearchTool(researchEngine = mockResearchEngine)

        val toolRegistry = DefaultToolRegistry().apply {
            registerTool(mockResearchTool)
            registerTool(GetDateTimeTool())
        }

        orchestrator = DefaultAiOrchestrator(
            intentDetector = intentDetector,
            contextManager = DefaultContextManager(),
            memoryManager = DefaultAiMemoryManager(),
            agentRegistry = DefaultAgentRegistry(),
            toolRegistry = toolRegistry
        )
    }

    @Test
    fun testIntentDetectorWebSearchAndResearchClassification() = runBlocking {
        assertEquals("WEB_SEARCH", intentDetector.detectIntent("Busca en Internet la población de Costa Rica"))
        assertEquals("WEB_SEARCH", intentDetector.detectIntent("Noticias de hoy sobre inteligencia artificial"))
        assertEquals("RESEARCH", intentDetector.detectIntent("Investiga las causas del cambio climático"))
    }

    @Test
    fun testMockWebSearchClientUnitTesting() = runBlocking {
        val sources = mockClient.search("Android 16", 2)
        assertEquals(2, sources.size)
        assertTrue(sources[0].url.contains("developer.android.com"))
        assertEquals("developer.android.com", sources[0].domain)
    }

    @Test
    fun testResearchEngineSynthesisWithRealSourcesModel() = runBlocking {
        val result = mockResearchEngine.investigate(ResearchRequest(query = "Costa Rica"))
        assertEquals(2, result.sources.size)
        assertTrue(result.summary.contains("Se analizaron 2 fuentes"))
        assertEquals("Costa Rica", result.query)
    }

    @Test
    fun testResearchToolExecutionOutput() = runBlocking {
        val output = mockResearchTool.execute(mapOf("query" to "Costa Rica"))
        assertTrue(output.contains("Investigación sobre 'Costa Rica'"))
        assertTrue(output.contains("Fuentes reales encontradas"))
    }

    @Test
    fun testPhase2NonRegressionOfflineTools() = runBlocking {
        assertEquals("DATE_TIME_QUERY", intentDetector.detectIntent("¿Qué hora es?"))
        val timeResponse = orchestrator.coordinate("¿Qué hora es?")
        assertTrue(timeResponse.contains("Son las"))

        assertEquals("CALCULATOR_QUERY", intentDetector.detectIntent("25 por 40"))
        val mathResponse = orchestrator.coordinate("25 por 40")
        assertTrue(mathResponse.contains("1000"))
    }
}
