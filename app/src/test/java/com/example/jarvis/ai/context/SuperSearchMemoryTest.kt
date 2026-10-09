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

class SuperSearchMemoryTest {

    private lateinit var context: ConversationContext

    @Before
    fun setUp() {
        context = ConversationContext()
    }

    @Test
    fun test1_setBusinessSearchResults_storesResultsAndActiveEntity() {
        val results = listOf(
            BusinessResultMemory(id = "1", name = "Minisúper Central", address = "Calle 1", phone = "2222-1111", rating = 4.5f, distanceKm = 1.2f),
            BusinessResultMemory(id = "2", name = "Minisúper El Sol", address = "Calle 5", phone = "2222-2222", rating = 4.8f, distanceKm = 0.5f),
            BusinessResultMemory(id = "3", name = "Minisúper San José", address = "Avenida 3", phone = "2222-3333", rating = 4.0f, distanceKm = 2.0f)
        )

        context.setBusinessSearchResults(results, "minisúper cercanos")

        assertEquals(3, context.lastBusinessResults.size)
        assertNotNull(context.activeEntity)
        assertEquals("Minisúper Central", context.activeEntity?.name)
        assertEquals("business", context.activeEntity?.type)
    }

    @Test
    fun test2_anaphora_closestWithKnownDistances_selectsClosest() {
        val results = listOf(
            BusinessResultMemory(id = "1", name = "Restaurante Lejos", distanceKm = 5.0f),
            BusinessResultMemory(id = "2", name = "Restaurante Cerca", distanceKm = 0.3f),
            BusinessResultMemory(id = "3", name = "Restaurante Medio", distanceKm = 1.8f)
        )
        context.setBusinessSearchResults(results, "restaurantes")

        val resolved = context.resolveAnaphora("¿Cuál queda más cerca?")

        assertTrue(resolved.contains("Restaurante Cerca"))
        assertTrue(resolved.contains("0.3 km"))
        assertEquals("Restaurante Cerca", context.activeEntity?.name)
    }

    @Test
    fun test3_anaphora_closestWithUnknownDistances_doesNotGuess() {
        val results = listOf(
            BusinessResultMemory(id = "1", name = "Restaurante A", distanceKm = null),
            BusinessResultMemory(id = "2", name = "Restaurante B", distanceKm = null)
        )
        context.setBusinessSearchResults(results, "restaurantes")

        val resolved = context.resolveAnaphora("¿Cuál queda más cerca?")

        assertTrue(resolved.contains("no se dispone de datos de distancia exactos"))
    }

    @Test
    fun test4_anaphora_topRatedWithKnownRatings_selectsHighestRating() {
        val results = listOf(
            BusinessResultMemory(id = "1", name = "Ferretería A", rating = 3.8f),
            BusinessResultMemory(id = "2", name = "Ferretería Estrella", rating = 4.9f),
            BusinessResultMemory(id = "3", name = "Ferretería B", rating = 4.1f)
        )
        context.setBusinessSearchResults(results, "ferreterías")

        val resolved = context.resolveAnaphora("¿Cuál tiene mejor valoración?")

        assertTrue(resolved.contains("Ferretería Estrella"))
        assertTrue(resolved.contains("4.9 estrellas"))
        assertEquals("Ferretería Estrella", context.activeEntity?.name)
    }

    @Test
    fun test5_anaphora_topRatedWithUnknownRatings_doesNotGuess() {
        val results = listOf(
            BusinessResultMemory(id = "1", name = "Farmacia A", rating = null),
            BusinessResultMemory(id = "2", name = "Farmacia B", rating = null)
        )
        context.setBusinessSearchResults(results, "farmacias")

        val resolved = context.resolveAnaphora("¿Cuál tiene mejor valoración?")

        assertTrue(resolved.contains("no se dispone de valoraciones numéricas registradas"))
    }

    @Test
    fun test6_anaphora_phoneQuery_refersToActiveEntity() {
        val results = listOf(
            BusinessResultMemory(id = "1", name = "Minsuper San Martín", phone = "8888-9999", address = "Barrio San Martín")
        )
        context.setBusinessSearchResults(results, "minsuper")

        val resolved = context.resolveAnaphora("¿Cuál es el teléfono?")

        assertTrue(resolved.contains("referente al negocio Minsuper San Martín"))
    }

    @Test
    fun test7_anaphora_openInMaps_resolvesActiveEntity() {
        val results = listOf(
            BusinessResultMemory(id = "1", name = "Ferretería El Llano", address = "San José Centro")
        )
        context.setBusinessSearchResults(results, "ferretería")

        val resolved = context.resolveAnaphora("Ábrelo en Google Maps")

        assertEquals("abre google maps con la ubicación de Ferretería El Llano", resolved)
    }

    @Test
    fun test8_businessResultMemory_isStaleDetection() {
        val fresh = BusinessResultMemory(id = "f1", name = "Fresh", retrievedAt = System.currentTimeMillis())
        val old = BusinessResultMemory(id = "o1", name = "Old", retrievedAt = System.currentTimeMillis() - 7200000L)

        assertFalse(fresh.isStale(3600000L))
        assertTrue(old.isStale(3600000L))
    }

    @Test
    fun test9_researchEngine_cacheStaleAndFresh() = runBlocking {
        val mockSearchClient = object : WebSearchClient {
            override suspend fun search(query: String, maxSources: Int): List<ResearchSource> {
                return listOf(
                    ResearchSource(
                        id = "s1",
                        url = "https://example.com/cr",
                        title = "Noticias Costa Rica",
                        domain = "example.com"
                    )
                )
            }
        }
        val engine = ResearchEngine(searchClient = mockSearchClient, cacheTtlMs = 100L)
        val req = ResearchRequest(query = "Noticias Costa Rica")

        val first = engine.investigate(req)
        assertFalse(first.isCached)

        val second = engine.investigate(req)
        assertTrue(second.isCached)

        @Suppress("DEPRECATION")
        (delay(150L))

        val third = engine.investigate(req)
        assertFalse(third.isCached)
    }

    @Test
    fun test10_fullChainSimulation_restaurantSearchAnaphoraAndMaps() {
        val searchResults = listOf(
            BusinessResultMemory(id = "1", name = "Restaurante Sabor Tico", address = "Calle Central 12", phone = "2255-0000", rating = 4.7f, distanceKm = 0.8f),
            BusinessResultMemory(id = "2", name = "Restaurante Rápido", address = "Av 2", phone = "2255-1111", rating = 4.2f, distanceKm = 0.2f),
            BusinessResultMemory(id = "3", name = "Restaurante Gourmet", address = "Av 10", phone = "2255-2222", rating = 4.9f, distanceKm = 3.5f)
        )

        context.setBusinessSearchResults(searchResults, "restaurante")
        assertEquals("Restaurante Sabor Tico", context.activeEntity?.name)

        val q1 = context.resolveAnaphora("¿Cuál queda más cerca?")
        assertTrue(q1.contains("Restaurante Rápido"))
        assertEquals("Restaurante Rápido", context.activeEntity?.name)

        val q2 = context.resolveAnaphora("¿Cuál es el teléfono?")
        assertTrue(q2.contains("referente al negocio Restaurante Rápido"))

        val q3 = context.resolveAnaphora("Ábrelo en Google Maps")
        assertEquals("abre google maps con la ubicación de Restaurante Rápido", q3)
    }
}
