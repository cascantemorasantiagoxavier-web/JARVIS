package com.example.jarvis.ai.context

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class RealStorageIntegrationTest {

    private lateinit var realStorageDir: File

    @Before
    fun setUp() {
        realStorageDir = File(System.getProperty("java.io.tmpdir"), "jarvis_real_storage_${System.currentTimeMillis()}")
        realStorageDir.mkdirs()
    }

    @After
    fun tearDown() {
        realStorageDir.deleteRecursively()
    }

    @Test
    fun testStrictJsonParser_rejectsInvalidJson() {
        val invalidJsons = listOf(
            "{ \"id\": \"1\" \"query\": \"sin coma\" }",
            "{ \"id\": \"1\", }",
            "{ id: \"1\" }",
            "{ \"id\": \"1\" } basura",
            "{ \"id\": \"1",
            "{\"id\":\"1\",\"x\":falsee}",
            "{\"id\":\"1\",\"x\":tru}",
            "{\"id\":\"1\",\"x\":nulo}",
            "{\"id\":\"1\",\"x\":--12}"
        )
        for (invalid in invalidJsons) {
            val result = DefaultSearchMemoryRepository.deserializeSearchMemoryEntry(invalid)
            assertNull("Expected invalid JSON to be rejected: $invalid", result)
        }
    }

    @Test
    fun testComplexEscapesAndUnicodeRoundtrip() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "esc_1",
            query = "Busca \"Supermercado Central\" \n ñ á é í ó ú \t \\ / \u00F1 \u00E1 🚀",
            searchType = "WEB_SEARCH",
            summary = "Ruta C:\\Users\\JARVIS\\memory\\test \r\n \"ñ á é í ó ú | ; ~ \\ \t \\\\\"",
            businessResults = listOf(
                BusinessResultMemory(
                    id = "b_esc",
                    name = "Negocio \"Especial\" \n\t ñáéíóú",
                    address = "Dir \\ Valida / Test"
                )
            )
        )

        val serialized = DefaultSearchMemoryRepository.serializeSearchMemoryEntry(entry)
        val deserialized = DefaultSearchMemoryRepository.deserializeSearchMemoryEntry(serialized)

        assertNotNull(deserialized)
        assertEquals(entry.query, deserialized?.query)
        assertEquals(entry.summary, deserialized?.summary)
        assertEquals(entry.businessResults.first().name, deserialized?.businessResults?.first()?.name)
        assertEquals(entry.businessResults.first().address, deserialized?.businessResults?.first()?.address)
    }

    @Test
    fun test21_realStorageDirectoryPersistence() = runBlocking {
        val repo = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val entry = SearchMemoryEntry(
            id = "real_21",
            query = "Búscame una ferretería en San José",
            searchType = "BUSINESS_SEARCH",
            summary = "Ferretería El Sol encontrada"
        )

        repo.saveSearch(entry)

        val files = realStorageDir.listFiles()
        assertNotNull(files)
        assertTrue(files!!.any { it.name == "search_real_21.json" })
    }

    @Test
    fun test22_recreateRepositoryInstanceFromRealStorage() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "real_22",
            query = "Restaurantes ticos",
            searchType = "BUSINESS_SEARCH",
            summary = "Sabor Tico",
            businessResults = listOf(BusinessResultMemory(id = "b22", name = "Sabor Tico", phone = "2222-3333"))
        )

        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(entry)

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val loaded = repoB.getSearch("real_22")

        assertNotNull(loaded)
        assertEquals("real_22", loaded?.id)
        assertEquals("Restaurantes ticos", loaded?.query)
        assertEquals(1, loaded?.businessResults?.size)
        assertEquals("Sabor Tico", loaded?.businessResults?.firstOrNull()?.name)
    }

    @Test
    fun test23_dataWithCommas() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "real_23",
            query = "Restaurante A, B, y C",
            searchType = "BUSINESS_SEARCH",
            summary = "Encontrados: A, B, C con buena comida",
            businessResults = listOf(BusinessResultMemory(id = "b23", name = "Sabor, Arte y Vino", address = "Calle 1, San José"))
        )

        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(entry)

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val loaded = repoB.getSearch("real_23")

        assertNotNull(loaded)
        assertEquals("Restaurante A, B, y C", loaded?.query)
        assertEquals("Sabor, Arte y Vino", loaded?.businessResults?.firstOrNull()?.name)
    }

    @Test
    fun test24_dataWithSemicolons() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "real_24",
            query = "Negocios; Categoría 1; Opción 2",
            searchType = "BUSINESS_SEARCH",
            summary = "Resumen; con; punto; y; coma",
            businessResults = listOf(BusinessResultMemory(id = "b24", name = "Librería; Papelería", address = "San José; Costa Rica"))
        )

        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(entry)

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val loaded = repoB.getSearch("real_24")

        assertNotNull(loaded)
        assertEquals("Negocios; Categoría 1; Opción 2", loaded?.query)
        assertEquals("Librería; Papelería", loaded?.businessResults?.firstOrNull()?.name)
    }

    @Test
    fun test25_dataWithTildesAndDiacritics() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "real_25",
            query = "¿Cuándo es el Día del Niño en Costa Rica?",
            searchType = "WEB_SEARCH",
            summary = "Celebración el 9 de septiembre con actividades~festivas"
        )

        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(entry)

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val loaded = repoB.getSearch("real_25")

        assertNotNull(loaded)
        assertEquals("¿Cuándo es el Día del Niño en Costa Rica?", loaded?.query)
        assertEquals("Celebración el 9 de septiembre con actividades~festivas", loaded?.summary)
    }

    @Test
    fun test26_dataWithPipeCharacters() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "real_26",
            query = "Opción A | Opción B | Opción C",
            searchType = "WEB_SEARCH",
            summary = "Resultado | con | barras | verticales"
        )

        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(entry)

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val loaded = repoB.getSearch("real_26")

        assertNotNull(loaded)
        assertEquals("Opción A | Opción B | Opción C", loaded?.query)
        assertEquals("Resultado | con | barras | verticales", loaded?.summary)
    }

    @Test
    fun test27_dataWithNewlines() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "real_27",
            query = "Consulta\ncon\nsaltos",
            searchType = "WEB_SEARCH",
            summary = "Resumen Línea 1\nResumen Línea 2\nResumen Línea 3"
        )

        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(entry)

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val loaded = repoB.getSearch("real_27")

        assertNotNull(loaded)
        assertEquals("Consulta\ncon\nsaltos", loaded?.query)
        assertEquals("Resumen Línea 1\nResumen Línea 2\nResumen Línea 3", loaded?.summary)
    }

    @Test
    fun test28_nullValuesPreservedAsNull() = runBlocking {
        val entry = SearchMemoryEntry(
            id = "real_28",
            query = "Negocio Sin Ratings ni Distancia",
            searchType = "BUSINESS_SEARCH",
            summary = "Campos opcionales en null",
            businessResults = listOf(
                BusinessResultMemory(id = "b28", name = "Minsuper X", rating = null, distanceKm = null)
            )
        )

        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(entry)

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val loaded = repoB.getSearch("real_28")?.businessResults?.firstOrNull()

        assertNotNull(loaded)
        assertNull(loaded?.rating)
        assertNull(loaded?.distanceKm)
    }

    @Test
    fun test29_corruptedFileHandling_skipsCorruptedLoadsValid() = runBlocking {
        val repoA = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        repoA.saveSearch(SearchMemoryEntry(id = "valid_1", query = "Búsqueda Válida 1", searchType = "WEB_SEARCH", summary = "Válida"))

        val corruptFile = File(realStorageDir, "search_corrupt_999.json")
        corruptFile.writeText("{ INVALID_CORRUPTED_JSON_DATA ### }", Charsets.UTF_8)

        repoA.saveSearch(SearchMemoryEntry(id = "valid_2", query = "Búsqueda Válida 2", searchType = "WEB_SEARCH", summary = "Válida 2"))

        val repoB = DefaultSearchMemoryRepository(storageDir = realStorageDir)

        val v1 = repoB.getSearch("valid_1")
        val v2 = repoB.getSearch("valid_2")
        val corrupt = repoB.getSearch("corrupt_999")

        assertNotNull(v1)
        assertNotNull(v2)
        assertNull(corrupt)
    }

    @Test
    fun test30_integrationSearchMemoryRepositoryToConversationContext() = runBlocking {
        val repo = DefaultSearchMemoryRepository(storageDir = realStorageDir)
        val conversationContext = ConversationContext()

        val bizList = listOf(
            BusinessResultMemory(id = "b30_1", name = "Minisúper La Amistad", address = "Barrio México", phone = "2211-0000", rating = 4.8f, distanceKm = 0.4f),
            BusinessResultMemory(id = "b30_2", name = "Minisúper Central", address = "San José Centro", phone = "2211-1111", rating = 4.2f, distanceKm = 1.5f)
        )

        repo.saveSearch(
            SearchMemoryEntry(
                id = "s_30",
                query = "Búscame minisúper cerca",
                searchType = "BUSINESS_SEARCH",
                summary = "2 minisúper encontrados",
                businessResults = bizList
            )
        )

        val retrievedEntry = repo.getSearch("s_30")
        assertNotNull(retrievedEntry)
        conversationContext.setBusinessSearchResults(retrievedEntry!!.businessResults, retrievedEntry.query)

        val resolvedClosest = conversationContext.resolveAnaphora("¿Cuál queda más cerca?")
        assertTrue(resolvedClosest.contains("Minisúper La Amistad"))

        val resolvedPhone = conversationContext.resolveAnaphora("¿Cuál es el teléfono?")
        assertTrue(resolvedPhone.contains("Minisúper La Amistad"))

        val resolvedMaps = conversationContext.resolveAnaphora("Ábrelo en Google Maps")
        assertEquals("abre google maps con la ubicación de Minisúper La Amistad", resolvedMaps)
    }
}
