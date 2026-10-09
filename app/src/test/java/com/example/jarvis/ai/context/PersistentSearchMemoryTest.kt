package com.example.jarvis.ai.context

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class PersistentSearchMemoryTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "jarvis_search_mem_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun test13_saveSearch_destroyInstance_recreate_retrievesExactEntry() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "s_001",
            query = "Búscame ferretería en San José",
            searchType = "BUSINESS_SEARCH",
            summary = "Se encontró Ferretería El Sol",
            businessResults = listOf(
                BusinessResultMemory(id = "b1", name = "Ferretería El Sol", address = "Calle 10", phone = "2222-1111", rating = 4.7f, distanceKm = 1.2f)
            )
        )

        // 1. Save using repo instance A
        val repo1 = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo1.saveSearch(entry)

        // 2. Destroy repo1 and instantiate new repo2 (simulating app restart)
        val repo2 = DefaultSearchMemoryRepository(storageDir = tempDir)
        val retrieved = repo2.getSearch("s_001")

        assertNotNull(retrieved)
        assertEquals("s_001", retrieved?.id)
        assertEquals("Búscame ferretería en San José", retrieved?.query)
        assertEquals("BUSINESS_SEARCH", retrieved?.searchType)
        assertEquals(1, retrieved?.businessResults?.size)
        assertEquals("Ferretería El Sol", retrieved?.businessResults?.get(0)?.name)
        assertEquals(4.7f, retrieved?.businessResults?.get(0)?.rating)
        assertEquals(1.2f, retrieved?.businessResults?.get(0)?.distanceKm)
    }

    @Test
    fun test14_saveSearch_deleteSearch_recreate_returnsNull() = runBlocking {
        val entry = SearchMemoryEntry(id = "s_002", query = "Restaurantes", searchType = "BUSINESS_SEARCH", summary = "3 resultados")

        val repo1 = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo1.saveSearch(entry)
        repo1.deleteSearch("s_002")

        val repo2 = DefaultSearchMemoryRepository(storageDir = tempDir)
        assertNull(repo2.getSearch("s_002"))
    }

    @Test
    fun test15_clearSearches_recreate_returnsNull() = runBlocking {
        val repo1 = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo1.saveSearch(SearchMemoryEntry(id = "s_003", query = "A", searchType = "WEB_SEARCH", summary = "Summary A"))
        repo1.saveSearch(SearchMemoryEntry(id = "s_004", query = "B", searchType = "WEB_SEARCH", summary = "Summary B"))

        repo1.clearSearches()

        val repo2 = DefaultSearchMemoryRepository(storageDir = tempDir)
        assertNull(repo2.getSearch("s_003"))
        assertNull(repo2.getSearch("s_004"))
    }

    @Test
    fun test16_completePersistenceOfBusinessResultMemoryFields() = runBlocking {
        val biz = BusinessResultMemory(
            id = "biz_777",
            name = "Restaurante Gourmet",
            category = "restaurante",
            address = "Av Escazú 100",
            phone = "2288-0000",
            website = "https://gourmet.cr",
            rating = 4.9f,
            distanceKm = 2.5f,
            sourceUrl = "https://gourmet.cr/location"
        )
        val entry = SearchMemoryEntry(id = "s_005", query = "Restaurante Gourmet", searchType = "BUSINESS_SEARCH", summary = "Detalles", businessResults = listOf(biz))

        val repo1 = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo1.saveSearch(entry)

        val repo2 = DefaultSearchMemoryRepository(storageDir = tempDir)
        val loaded = repo2.getSearch("s_005")?.businessResults?.firstOrNull()

        assertNotNull(loaded)
        assertEquals("biz_777", loaded?.id)
        assertEquals("Restaurante Gourmet", loaded?.name)
        assertEquals("restaurante", loaded?.category)
        assertEquals("Av Escazú 100", loaded?.address)
        assertEquals("2288-0000", loaded?.phone)
        assertEquals("https://gourmet.cr", loaded?.website)
        assertEquals(4.9f, loaded?.rating)
        assertEquals(2.5f, loaded?.distanceKm)
    }

    @Test
    fun test17_preservationOfNullRatingAndDistance() = runBlocking {
        val bizWithNulls = BusinessResultMemory(
            id = "biz_nulls",
            name = "Negocio Sin Ratings ni Distancia",
            rating = null,
            distanceKm = null
        )
        val entry = SearchMemoryEntry(id = "s_006", query = "Negocio Desconocido", searchType = "BUSINESS_SEARCH", summary = "Null test", businessResults = listOf(bizWithNulls))

        val repo1 = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo1.saveSearch(entry)

        val repo2 = DefaultSearchMemoryRepository(storageDir = tempDir)
        val loaded = repo2.getSearch("s_006")?.businessResults?.firstOrNull()

        assertNotNull(loaded)
        assertNull(loaded?.rating)
        assertNull(loaded?.distanceKm)
    }

    @Test
    fun test18_findRelevantSearches_ranksExactMatchHighest() = runBlocking {
        val repo = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo.saveSearch(SearchMemoryEntry(id = "1", query = "noticias", searchType = "WEB_SEARCH", summary = "Noticias generales"))
        repo.saveSearch(SearchMemoryEntry(id = "2", query = "noticias de costa rica", searchType = "WEB_SEARCH", summary = "Noticias Costa Rica"))

        val results = repo.findRelevantSearches("noticias de costa rica")

        assertEquals(2, results.size)
        assertEquals("2", results[0].id) // Exact match ranked first!
    }

    @Test
    fun test19_findRelevantSearches_partialWordMatches() = runBlocking {
        val repo = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo.saveSearch(SearchMemoryEntry(id = "1", query = "investigar energía solar", searchType = "RESEARCH", summary = "Solar energy"))

        val results = repo.findRelevantSearches("energía")

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun test20_differenceBetweenWebSearchAndBusinessSearchStructure() = runBlocking {
        val webEntry = SearchMemoryEntry(id = "web_1", query = "Noticias Tesla", searchType = "WEB_SEARCH", summary = "Artículos Tesla", sourceUrls = listOf("https://tesla.com/news"))
        val bizEntry = SearchMemoryEntry(id = "biz_1", query = "Minisúper cerca", searchType = "BUSINESS_SEARCH", summary = "Locales encontrados", businessResults = listOf(BusinessResultMemory(id = "b1", name = "Minisúper X")))

        val repo1 = DefaultSearchMemoryRepository(storageDir = tempDir)
        repo1.saveSearch(webEntry)
        repo1.saveSearch(bizEntry)

        val repo2 = DefaultSearchMemoryRepository(storageDir = tempDir)
        val loadedWeb = repo2.getSearch("web_1")
        val loadedBiz = repo2.getSearch("biz_1")

        assertTrue(loadedWeb?.businessResults?.isEmpty() == true)
        assertEquals(1, loadedWeb?.sourceUrls?.size)

        assertEquals(1, loadedBiz?.businessResults?.size)
        assertEquals("BUSINESS_SEARCH", loadedBiz?.searchType)
    }
}
