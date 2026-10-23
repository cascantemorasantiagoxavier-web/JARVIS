package com.example.jarvis.core

import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.ToolRun
import com.example.jarvis.core.realtime.PriceTable
import com.example.jarvis.core.realtime.RealtimeConnectRequest
import com.example.jarvis.core.realtime.RealtimeConnector
import com.example.jarvis.core.realtime.RealtimeProtocol
import com.example.jarvis.core.realtime.RealtimeSessionController
import com.example.jarvis.core.realtime.RealtimeSettings
import com.example.jarvis.core.realtime.RealtimeTransport
import com.example.jarvis.core.realtime.ServerEvent
import com.example.jarvis.core.realtime.TransportState
import com.example.jarvis.core.realtime.VoiceEventSink
import com.example.jarvis.core.realtime.VoiceState
import com.example.jarvis.core.tools.WorkingContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTransport : RealtimeTransport {
    val sent = mutableListOf<String>()
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 100)
    override val state = MutableStateFlow(TransportState.OPEN)
    var closed = false
    override fun send(json: String): Boolean { sent += json; return true }
    override suspend fun close() { closed = true; state.value = TransportState.CLOSED }
    fun types() = sent.map { JarvisJson.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content }
    suspend fun server(json: String) = messages.emit(json)
}

class RecordingVoiceSink : VoiceEventSink {
    val user = mutableListOf<String>()
    val assistant = mutableListOf<Triple<String, List<ResultCard>, List<ToolRun>>>()
    val notices = mutableListOf<String>()
    val ended = mutableListOf<String>()
    override fun userSaid(text: String) { user += text }
    override fun assistantSaid(text: String, cards: List<ResultCard>, runs: List<ToolRun>) { assistant += Triple(text, cards, runs) }
    override fun notice(text: String) { notices += text }
    override fun sessionEnded(reason: String) { ended += reason }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeTest {
    @Test fun sessionUpdateShapeMatchesGaApi() {
        val kit = TestKit()
        val json = JarvisJson.parseToJsonElement(RealtimeProtocol.sessionUpdate("Eres JARVIS", kit.registry.definitionsFor(true), 1.1)).jsonObject
        assertEquals("session.update", json["type"]!!.jsonPrimitive.content)
        val s = json["session"]!!.jsonObject
        assertEquals("realtime", s["type"]!!.jsonPrimitive.content)
        assertEquals("auto", s["tool_choice"]!!.jsonPrimitive.content)
        val tools = s["tools"]!!.jsonArray
        assertTrue(tools.size >= 30)
        val first = tools.first().jsonObject
        assertEquals("function", first["type"]!!.jsonPrimitive.content)
        assertTrue(first.containsKey("parameters"))
        assertEquals("1.1", s["audio"]!!.jsonObject["output"]!!.jsonObject["speed"]!!.jsonPrimitive.content)
        val out = JarvisJson.parseToJsonElement(RealtimeProtocol.functionCallOutput("call_9", """{"ok":true}""")).jsonObject
        assertEquals("conversation.item.create", out["type"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", out["item"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_9", out["item"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
    }

    @Test fun parsesServerEvents() {
        assertEquals(ServerEvent.SpeechStarted, RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_started","event_id":"e1","audio_start_ms":10,"item_id":"i"}"""))
        val done = RealtimeProtocol.parse(
            """{"type":"response.done","event_id":"e","response":{"id":"resp_1","status":"completed","output":[
              {"type":"function_call","call_id":"call_1","name":"get_datetime","arguments":"{}"},
              {"type":"message","content":[{"type":"output_audio","transcript":"Hola"}]}],
              "usage":{"input_tokens":100,"output_tokens":50,"input_token_details":{"audio_tokens":80,"text_tokens":20,"cached_tokens":30,"cached_tokens_details":{"audio_tokens":25}},"output_token_details":{"audio_tokens":45,"text_tokens":5}}}}""",
        ) as ServerEvent.ResponseDone
        assertEquals("resp_1", done.responseId)
        assertEquals("call_1", done.functionCalls.single().callId)
        assertTrue(done.hadAudio)
        assertEquals(80L, done.usage!!.audioIn)
        assertEquals(25L, done.usage!!.cachedAudioIn)
        val err = RealtimeProtocol.parse("""{"type":"error","event_id":"e","error":{"type":"invalid_request_error","code":"session_expired","message":"Session expired"}}""") as ServerEvent.Error
        assertEquals("session_expired", err.code)
        assertTrue(RealtimeProtocol.parse("no es json") is ServerEvent.Other)
    }

    private class Harness(scope: TestScope, val settings: RealtimeSettings = RealtimeSettings(idleTimeoutSeconds = 30, maxSessionMinutes = 10, maxReconnects = 1)) {
        val kit = TestKit()
        val sink = RecordingVoiceSink()
        val transports = mutableListOf<FakeTransport>()
        var failConnect = false
        val requests = mutableListOf<RealtimeConnectRequest>()
        val controller = RealtimeSessionController(
            connector = RealtimeConnector { req ->
                requests += req
                if (failConnect) error("backend caído")
                FakeTransport().also { transports += it }
            },
            registry = kit.registry,
            executor = kit.executor,
            instructions = { "Eres JARVIS" },
            settings = { settings },
            working = WorkingContext(),
            ui = kit.ui,
            sink = sink,
            time = kit.time,
            scope = scope.backgroundScope,
        )
        val t get() = transports.last()
    }

    @Test fun fullTurnWithToolCallInterruptionAndUsage() = runTest {
        val h = Harness(this)
        assertTrue(h.controller.start())
        runCurrent()
        assertEquals(VoiceState.CONNECTING, h.controller.state.value)
        assertEquals("cedar", h.requests.single().voice)
        h.t.server("""{"type":"session.created","session":{"model":"gpt-realtime-2.1-mini","audio":{"output":{"voice":"cedar"}}}}""")
        runCurrent()
        assertEquals(listOf("session.update"), h.t.types())
        assertEquals(VoiceState.LISTENING, h.controller.state.value)

        h.t.server("""{"type":"input_audio_buffer.speech_started"}"""); runCurrent()
        assertEquals(VoiceState.USER_SPEAKING, h.controller.state.value)
        h.t.server("""{"type":"input_audio_buffer.speech_stopped"}"""); runCurrent()
        assertEquals(VoiceState.THINKING, h.controller.state.value)
        h.t.server("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"¿Qué hora es?"}"""); runCurrent()
        assertEquals(listOf("¿Qué hora es?"), h.sink.user)

        // El modelo pide una herramienta
        h.t.server("""{"type":"response.created","response":{"id":"r1"}}""")
        h.t.server("""{"type":"response.function_call_arguments.done","response_id":"r1","call_id":"c1","name":"get_datetime","arguments":"{}"}""")
        runCurrent()
        h.t.server("""{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"type":"function_call","call_id":"c1","name":"get_datetime","arguments":"{}"}],"usage":{"input_tokens":10,"output_tokens":5,"input_token_details":{"audio_tokens":10},"output_token_details":{"text_tokens":5}}}}""")
        runCurrent()
        val types = h.t.types()
        assertEquals("conversation.item.create", types[1])
        assertTrue(h.t.sent[1].contains("function_call_output") && h.t.sent[1].contains("07:42"))
        assertEquals("response.create", types[2])

        // Respuesta hablada
        h.t.server("""{"type":"response.created","response":{"id":"r2"}}""")
        h.t.server("""{"type":"output_audio_buffer.started"}"""); runCurrent()
        assertEquals(VoiceState.SPEAKING, h.controller.state.value)
        h.t.server("""{"type":"response.output_audio_transcript.done","response_id":"r2","transcript":"Son las 7:42, jefe."}"""); runCurrent()
        assertEquals("Son las 7:42, jefe.", h.sink.assistant.single().first)
        assertEquals(listOf("get_datetime"), h.sink.assistant.single().third.map { it.name })

        // Interrupción manual mientras habla
        h.controller.interrupt(); runCurrent()
        assertEquals(listOf("response.cancel", "output_audio_buffer.clear"), h.t.types().takeLast(2))
        assertEquals(VoiceState.LISTENING, h.controller.state.value)

        assertEquals(10L, h.controller.usage.value.audioIn)
        assertTrue(h.controller.usage.value.estimatedCostUsd(PriceTable.MINI) > 0)
        h.controller.stop("prueba"); runCurrent()
        assertTrue(h.t.closed)
        assertEquals(VoiceState.OFF, h.controller.state.value)
        assertEquals(listOf("prueba"), h.sink.ended)
    }

    @Test fun idleTimeoutClosesSessionToSaveCost() = runTest {
        val h = Harness(this, RealtimeSettings(idleTimeoutSeconds = 5))
        h.controller.start(); runCurrent()
        h.t.server("""{"type":"session.created","session":{}}"""); runCurrent()
        advanceTimeBy(7_000); runCurrent()
        // El reloj de negocio es FixedTimeSource: lo avanzamos igual que el tiempo virtual
        h.kit.time.advanceSeconds(7)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(listOf("inactividad"), h.sink.ended)
        assertTrue(h.t.closed)
    }

    @Test fun maxDurationIsEnforced() = runTest {
        val h = Harness(this, RealtimeSettings(idleTimeoutSeconds = 10_000, maxSessionMinutes = 1))
        h.controller.start(); runCurrent()
        h.t.server("""{"type":"session.created","session":{}}"""); runCurrent()
        h.kit.time.advanceSeconds(61)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(listOf("duración máxima"), h.sink.ended)
    }

    @Test fun connectionFailureIsReportedNotHidden() = runTest {
        val h = Harness(this)
        h.failConnect = true
        assertFalse(h.controller.start())
        assertEquals(VoiceState.ERROR, h.controller.state.value)
        assertTrue(h.controller.lastError.value!!.contains("backend caído"))
        assertTrue(h.sink.notices.single().startsWith("Voz no disponible"))
    }

    @Test fun reconnectsAFiniteNumberOfTimes() = runTest {
        val h = Harness(this, RealtimeSettings(maxReconnects = 1))
        h.controller.start(); runCurrent()
        h.t.state.value = TransportState.FAILED; runCurrent()
        advanceTimeBy(1_500); runCurrent()
        assertEquals(2, h.transports.size)
        assertTrue(h.sink.notices.any { it.contains("restablecida") })
        h.failConnect = true
        h.t.state.value = TransportState.FAILED; runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("conexión perdida"), h.sink.ended)
        assertEquals(VoiceState.ERROR, h.controller.state.value)
        assertEquals(2, h.transports.size) // no hay reintentos infinitos
        advanceTimeBy(3_000); runCurrent()
        assertEquals("el estado de error es transitorio", VoiceState.OFF, h.controller.state.value)
    }

    @Test fun endConversationToolStopsAfterGoodbye() = runTest {
        val h = Harness(this)
        h.controller.start(); runCurrent()
        h.t.server("""{"type":"session.created","session":{}}""")
        h.t.server("""{"type":"response.function_call_arguments.done","response_id":"r1","call_id":"c1","name":"end_conversation","arguments":"{}"}""")
        h.t.server("""{"type":"response.done","response":{"id":"r1","status":"completed","output":[]}}""")
        runCurrent()
        h.t.server("""{"type":"output_audio_buffer.started"}""")
        h.t.server("""{"type":"output_audio_buffer.stopped"}""")
        runCurrent()
        assertEquals(listOf("conversación terminada"), h.sink.ended)
    }

    @Test fun serverErrorsAreSurfaced() = runTest {
        val h = Harness(this)
        h.controller.start(); runCurrent()
        h.t.server("""{"type":"error","error":{"type":"invalid_request_error","code":"conversation_already_has_active_response","message":"x"}}""")
        h.t.server("""{"type":"error","error":{"type":"server_error","code":"boom","message":"Fallo del servidor"}}""")
        runCurrent()
        assertEquals("Fallo del servidor", h.controller.lastError.value)
        assertEquals(1, h.sink.notices.size)
        h.t.server("""{"type":"error","error":{"type":"invalid_request_error","code":"session_expired","message":"Session expired"}}""")
        runCurrent()
        assertEquals(listOf("la sesión expiró"), h.sink.ended)
    }
}
