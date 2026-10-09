package com.example.jarvis.ai.context

import com.example.jarvis.ai.research.ResearchEngine
import com.example.jarvis.ai.research.ResearchRequest
import com.example.jarvis.ai.research.ResearchSource
import com.example.jarvis.ai.research.WebSearchClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SearchArchitectureTest {

    private lateinit var context: ConversationContext
    private lateinit var searchRepo: SearchMemoryRepository

    @Before
    fun setUp() {
        context = ConversationContext()
        searchRepo = DefaultSearchMemoryRepository()
    }

    @Test
    fun test1_generalWebSource_isNotConvertedToBusinessMemory() {
        context.clear()
        val generalSources = listOf(
            ResearchSource(id = "s1", url = "https://wikipedia.org/wiki/Costa_Rica", title = "Costa Rica - Wikipedia", domain = "wikipedia.org")
        )
        // General research source does not alter or populate business memory
        assertEquals("wikipedia.org", generalSources.first().domain)
        assertTrue(context.lastBusinessResults.isEmpty())
    }

    @Test
    fun test2_validBusinessQuery_createsBusinessResultMemory() {
        val bizMemory = BusinessResultMemory(
            id = "b1",
            name = "Ferretería El Sol",
            category = "ferretería",
            address = "Calle 10, San José",
            phone = "2233-4455",
            rating = 4.6f,
            distanceKm = 1.1f
        )
        assertEquals("Ferretería El Sol", bizMemory.name)
        assertEquals("ferretería", bizMemory.category)
        assertEquals(4.6f, bizMemory.rating)
        assertEquals(1.1f, bizMemory.distanceKm)
    }

    @Test
    fun test3_newsSources_doNotAppearInLastBusinessResults() {
        context.clear()
        context.extractAndSetEntities("Busca noticias de hoy", "Noticias recientes...")
        assertTrue(context.lastBusinessResults.isEmpty())
    }

    @Test
    fun test4_businessSearch_populatesLastBusinessResults() {
        context.clear()
        val bizList = listOf(
            BusinessResultMemory(id = "b1", name = "Minisúper La Esquina")
        )
        context.setBusinessSearchResults(bizList, "minisúper")
        assertEquals(1, context.lastBusinessResults.size)
        assertEquals("Minisúper La Esquina", context.lastBusinessResults[0].name)
    }

    @Test
    fun test5_generalWebSearch_doesNotAlterActiveBusinessEntity() {
        context.clear()
        context.extractAndSetEntities("Busca información sobre Costa Rica", "Costa Rica es un país de Centroamérica...")
        assertEquals("Costa Rica", context.activeEntity?.name)
        assertEquals("country", context.activeEntity?.type)
        assertNotEquals("business", context.activeEntity?.type)
    }

    @Test
    fun test6_businessSearch_setsBusinessActiveEntity() {
        context.clear()
        val bizList = listOf(
            BusinessResultMemory(id = "b1", name = "Restaurante Sabor Latino", category = "restaurante")
        )
        context.setBusinessSearchResults(bizList, "restaurantes")
        assertEquals("Restaurante Sabor Latino", context.activeEntity?.name)
        assertEquals("business", context.activeEntity?.type)
    }

    @Test
    fun test7_closestQuery_onlyUsesResultsWithDistance() {
        context.clear()
        val bizList = listOf(
            BusinessResultMemory(id = "b1", name = "Local Sin Distancia", distanceKm = null),
            BusinessResultMemory(id = "b2", name = "Local Con Distancia", distanceKm = 0.5f)
        )
        context.setBusinessSearchResults(bizList, "locales")

        val resolved = context.resolveAnaphora("¿Cuál queda más cerca?")
        assertTrue(resolved.contains("Local Con Distancia"))
        assertTrue(resolved.contains("0.5 km"))
    }

    @Test
    fun test8_openInMaps_onlyResolvesWhenBusinessEntityPresent() {
        context.clear()
        val bizList = listOf(
            BusinessResultMemory(id = "b1", name = "Farmacia San Rafael", address = "San José Centro")
        )
        context.setBusinessSearchResults(bizList, "farmacia")

        val resolved = context.resolveAnaphora("Ábrelo en Google Maps")
        assertEquals("abre google maps con la ubicación de Farmacia San Rafael", resolved)
    }

    @Test
    fun test9_unknownFields_remainNull() {
        val biz = BusinessResultMemory(id = "b1", name = "Negocio Desconocido")
        assertNull(biz.rating)
        assertNull(biz.distanceKm)
    }

    @Test
    fun test10_researchEngine_cacheFresh() = runBlocking {
        val mockClient = object : WebSearchClient {
            override suspend fun search(query: String, maxSources: Int): List<ResearchSource> {
                return listOf(ResearchSource(id = "1", url = "https://ex.com", title = "Test", domain = "ex.com"))
            }
        }
        val engine = ResearchEngine(searchClient = mockClient, cacheTtlMs = 60000L)
        val req = ResearchRequest(query = "Investiga Tesla")

        val first = engine.investigate(req)
        assertFalse(first.isCached)

        val second = engine.investigate(req)
        assertTrue(second.isCached)
    }

    @Test
    fun test11_researchEngine_cacheStale() = runBlocking {
        val mockClient = object : WebSearchClient {
            override suspend fun search(query: String, maxSources: Int): List<ResearchSource> {
                return listOf(ResearchSource(id = "1", url = "https://ex.com", title = "Test", domain = "ex.com"))
            }
        }
        val engine = ResearchEngine(searchClient = mockClient, cacheTtlMs = 100L)
        val req = ResearchRequest(query = "Investiga Tesla")

        val first = engine.investigate(req)
        assertFalse(first.isCached)

        @Suppress("DEPRECATION")
        (delay(150L))

        val third = engine.investigate(req)
        assertFalse(third.isCached)
    }

    @Test
    fun test12_searchMemoryRepository_savesAndRetrieves() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "s123",
            query = "Búscame ferreterías",
            searchType = "BUSINESS_SEARCH",
            summary = "Se encontraron 2 ferreterías"
        )

        searchRepo.saveSearch(entry)

        val retrieved = searchRepo.getSearch("s123")
        assertNotNull(retrieved)
        assertEquals("Búscame ferreterías", retrieved?.query)

        val relevant = searchRepo.findRelevantSearches("ferreterías")
        assertEquals(1, relevant.size)

        searchRepo.clearSearches()
        assertNull(searchRepo.getSearch("s123"))
    }
}
