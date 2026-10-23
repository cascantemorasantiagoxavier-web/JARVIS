package com.example.jarvis.core

import com.example.jarvis.core.brain.BackendLlmClient
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.core.model.JarvisRequest
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.net.BackendClient
import com.example.jarvis.core.net.BackendConfig
import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.net.BusinessSearchRequest
import com.example.jarvis.core.net.RealtimeCallRequest
import com.example.jarvis.core.net.RealtimeSessionRequest
import com.example.jarvis.core.net.WebSearchRequest
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.WorkingContext
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Collections

/**
 * Integración real Kotlin ↔ backend Node.js. Arranca `node src/server.js` y sustituye a OpenAI,
 * Nominatim, Overpass y SearXNG por un servidor HTTP local (respuestas sintéticas con la forma
 * documentada de cada API). Verifica el contrato completo entre la app y el servidor.
 */
class BackendIntegrationTest {
    companion object {
        private const val TOKEN = "integration-token-0123456789abcdef"
        private const val OPENAI_KEY = "sk-integration-PERMANENT-KEY-0000"
        private val upstream = MockWebServer()
        private val seen: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
        private var node: Process? = null
        private var baseUrl = ""

        private val backendDir = File("../../../backend").canonicalFile

        private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

        @BeforeClass @JvmStatic fun startStack() {
            val nodeOk = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
            assumeTrue("node no disponible", nodeOk)
            assumeTrue("faltan dependencias del backend (npm install)", File(backendDir, "node_modules/express").exists())

            upstream.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    seen += request
                    val path = request.path.orEmpty()
                    return when {
                        path == "/v1/realtime/client_secrets" -> json("""{"value":"ek_integration","expires_at":1900000000,"session":{"type":"realtime"}}""")
                        path == "/v1/realtime/calls" -> MockResponse().setResponseCode(201).setHeader("location", "/v1/realtime/calls/rtc_int1").setBody("v=0\r\ns=answer\r\n")
                        path == "/v1/responses" && body.contains("function_call_output") ->
                            json("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Encontré supermercados reales en Heredia según OpenStreetMap."}]}]}""")
                        path == "/v1/responses" ->
                            json("""{"status":"completed","output":[{"type":"function_call","call_id":"call_x","name":"business_search","arguments":"{\"category\":\"supermercados\",\"location\":\"Heredia\"}"}]}""")
                        path.startsWith("/search?") && path.contains("format=jsonv2") ->
                            json("""[{"osm_type":"relation","osm_id":1104300,"lat":"10.0","lon":"-84.1","name":"Heredia","display_name":"Heredia, Costa Rica","category":"boundary","type":"administrative","addresstype":"county","importance":0.6}]""")
                        path.startsWith("/search?") && path.contains("format=json") ->
                            json("""{"results":[{"title":"Requisitos de importación","url":"https://www.hacienda.go.cr/importar","content":"Guía oficial","publishedDate":"2026-01-10"}]}""")
                        path == "/api/interpreter" -> {
                            val q = URLDecoder.decode(body.removePrefix("data="), "UTF-8")
                            if (q.contains("foreach")) {
                                json("""{"elements":[{"type":"node","id":501},{"type":"area","tags":{"admin_level":"4","name":"Heredia"}},{"type":"area","tags":{"admin_level":"6","name":"Heredia"}}]}""")
                            } else {
                                json("""{"elements":[
                                  {"type":"node","id":501,"lat":10.0,"lon":-84.11,"tags":{"name":"Súper Integración","shop":"supermarket","phone":"2260 0000"}},
                                  {"type":"way","id":502,"center":{"lat":10.01,"lon":-84.12},"tags":{"name":"Mercado Sin Teléfono","shop":"supermarket"}}]}""")
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            upstream.start()
            val up = upstream.url("/").toString().trimEnd('/')
            val port = ServerSocket(0).use { it.localPort }
            val pb = ProcessBuilder("node", "src/server.js").directory(backendDir).redirectErrorStream(true)
            pb.environment().apply {
                put("PORT", port.toString()); put("HOST", "127.0.0.1")
                put("JARVIS_CLIENT_TOKEN", TOKEN); put("OPENAI_API_KEY", OPENAI_KEY)
                put("OPENAI_BASE_URL", "$up/v1"); put("OVERPASS_URL", "$up/api/interpreter"); put("NOMINATIM_URL", up)
                put("SEARCH_PROVIDERS", "searxng"); put("SEARXNG_URL", up); put("OSM_CONTACT", "integration-test"); put("LOG_LEVEL", "warn")
            }
            node = pb.start()
            baseUrl = "http://127.0.0.1:$port"
            val probe = BackendClient({ BackendConfig(baseUrl, TOKEN) })
            val ready = (1..50).any { runCatching { runBlocking { probe.health() } }.isSuccess.also { ok -> if (!ok) Thread.sleep(200) } }
            check(ready) { "El backend no arrancó: " + node!!.inputStream.bufferedReader().readText() }
        }

        @AfterClass @JvmStatic fun stopStack() {
            node?.destroy()
            runCatching { upstream.shutdown() }
        }
    }

    private fun client(token: String = TOKEN) = BackendClient({ BackendConfig(baseUrl, token) })

    @Test fun healthAndAuth() = runBlocking {
        val h = client().health()
        assertEquals("ok", h.status)
        assertTrue(h.features.realtime && h.features.chat)
        assertEquals(listOf("searxng"), h.features.webSearch)
        try {
            client("token-incorrecto-0123456789abcdefgh").usage()
            throw AssertionError("debió fallar")
        } catch (e: BackendException) {
            assertEquals(401, e.httpStatus)
            assertTrue(e.userMessage.contains("token"))
        }
    }

    @Test fun realtimeSessionKeepsPermanentKeyOnServer() = runBlocking {
        val s = client().realtimeSession(RealtimeSessionRequest(voice = "cedar"))
        assertEquals("ek_integration", s.clientSecret)
        assertEquals("gpt-realtime-2.1-mini", s.model)
        assertFalse(s.toString().contains(OPENAI_KEY))
        val upstreamCall = seen.last { it.path == "/v1/realtime/client_secrets" }
        assertEquals("Bearer $OPENAI_KEY", upstreamCall.getHeader("Authorization"))

        val call = client().realtimeCall(RealtimeCallRequest(sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n", voice = "cedar", maxSessionMinutes = 5))
        assertEquals("v=0\r\ns=answer\r\n", call.sdp)
        assertEquals("rtc_int1", call.callId)
    }

    @Test fun businessSearchContractWithFieldStatuses() = runBlocking {
        val r = client().businessSearch(BusinessSearchRequest("supermercados", "Heredia"))
        assertEquals(2, r.total)
        val withPhone = r.results.first { it.id == "osm:node:501" }
        assertEquals("+506 2260-0000", withPhone.phone.value)
        assertEquals(FieldStatus.FOUND, withPhone.phone.status)
        assertEquals("Heredia", withPhone.province.value)
        val noPhone = r.results.first { it.id == "osm:way:502" }
        assertEquals(FieldStatus.UNAVAILABLE, noPhone.phone.status)
        assertEquals(null, noPhone.phone.value)
        assertEquals(FieldStatus.APPROXIMATE, noPhone.coordinates.status)
        assertTrue(r.source!!.license!!.contains("ODbL"))
    }

    @Test fun webSearchThroughBackend() = runBlocking {
        val r = client().webSearch(WebSearchRequest("requisitos importación"))
        assertEquals("searxng", r.provider)
        assertEquals("https://www.hacienda.go.cr/importar", r.results.single().url)
        assertEquals("2026-01-10T00:00:00.000Z", r.results.single().publishedAt)
    }

    @Test fun endToEndToolLoopModelBackendAndOsm() = runBlocking {
        val kit = TestKit(backendUrl = baseUrl, token = TOKEN)
        kit.llm = BackendLlmClient(kit.backend)
        val working = WorkingContext()
        val r = kit.brain.handle(JarvisRequest("Encuentra negocios que puedan comprar café en Heredia"), ToolContext(com.example.jarvis.core.model.RequestSource.TEXT, kit.ui, working)) {}
        assertEquals("Encontré supermercados reales en Heredia según OpenStreetMap.", r.text)
        assertTrue(r.usedModel)
        assertEquals(listOf("business_search"), r.toolRuns.map { it.name })
        assertTrue(r.toolRuns.single().ok)
        val card = r.cards.filterIsInstance<ResultCard.Businesses>().single()
        assertEquals(2, card.result.results.size)
        assertEquals(2, working.lastBusinesses().size)
        // La segunda ronda al modelo llevó el resultado real de la herramienta
        val second = seen.filter { it.path == "/v1/responses" }.last()
        val sentBody = second.body.clone().readUtf8()
        assertTrue(sentBody.isEmpty() || sentBody.contains("function_call_output"))
    }
}
