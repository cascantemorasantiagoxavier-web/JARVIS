package com.example.jarvis.ai.research

import com.example.jarvis.ai.tools.ResearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ResearchEngineTest {

    private lateinit var engine: ResearchEngine

    @Before
    fun setUp() {
        engine = ResearchEngine(searchClient = MockWebSearchClient())
    }

    @Test
    fun testResearchRequestCreation() {
        val req = ResearchRequest(query = "Android Architecture", depth = ResearchDepth.DEEP)
        assertEquals("Android Architecture", req.query)
        assertEquals(ResearchDepth.DEEP, req.depth)
    }

    @Test
    fun testResearchSourceAndEvidence() {
        val evidence = Evidence(sourceId = "s1", claim = "Claim 1", excerpt = "Excerpt 1")
        val source = ResearchSource(url = "https://example.com", title = "Example", domain = "example.com", extractedEvidence = listOf(evidence))
        assertEquals(1, source.extractedEvidence.size)
        assertEquals("Claim 1", source.extractedEvidence[0].claim)
    }

    @Test
    fun testSourceEvaluationAndCredibility() {
        val source = ResearchSource(url = "https://android.com", title = "Android", domain = "android.com", credibilityScore = 0.95f)
        assertTrue(source.credibilityScore > 0.9f)
    }

    @Test
    fun testClaimAndConflictDetection() {
        val claim = Claim(statement = "Test claim", status = ClaimStatus.SUPPORTED)
        assertEquals(ClaimStatus.SUPPORTED, claim.status)

        val conflict = ResearchConflict(claimA = "A", sourceA = "dom1", claimB = "B", sourceB = "dom2")
        assertEquals("A", conflict.claimA)
    }

    @Test
    fun testResearchExecutionAndSynthesis() = runBlocking {
        val req = ResearchRequest(query = "Kotlin Coroutines conflict", depth = ResearchDepth.STANDARD)
        val result = engine.investigate(req)
        assertNotNull(result)
        assertTrue(result.sources.isNotEmpty())
        assertTrue(result.summary.contains("Kotlin Coroutines conflict"))
    }

    @Test
    fun testResearchCacheReuse() = runBlocking {
        val req = ResearchRequest(query = "Offline sync", depth = ResearchDepth.QUICK)
        val res1 = engine.investigate(req)
        assertFalse(res1.isCached)

        val res2 = engine.investigate(req)
        assertTrue(res2.isCached)
    }

    @Test
    fun testResearchToolExecution() = runBlocking {
        val tool = ResearchTool(engine)
        assertEquals("research_topic", tool.name)
        val output = tool.execute(mapOf("query" to "Jetpack Compose"))
        assertTrue(output.contains("Jetpack Compose"))
    }
}
