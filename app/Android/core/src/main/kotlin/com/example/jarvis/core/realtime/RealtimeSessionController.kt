package com.example.jarvis.core.realtime

import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.ToolRun
import com.example.jarvis.core.model.UiEventSink
import com.example.jarvis.core.tools.ToolCall
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.ToolExecutor
import com.example.jarvis.core.tools.ToolRegistry
import com.example.jarvis.core.tools.ToolResult
import com.example.jarvis.core.tools.WorkingContext
import com.example.jarvis.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class TransportState { CONNECTING, OPEN, CLOSED, FAILED }

/** Conexión abierta con Realtime (WebRTC en Android). Los mensajes son JSON del canal "oai-events". */
interface RealtimeTransport {
    val messages: Flow<String>
    val state: StateFlow<TransportState>
    fun send(json: String): Boolean
    suspend fun close()
}

data class RealtimeConnectRequest(
    val model: String?,
    val voice: String,
    val speed: Double?,
    val instructions: String,
    val maxSessionMinutes: Int,
)

/** Negocia la sesión (SDP vía backend) y devuelve el transporte con el canal de datos abierto. */
fun interface RealtimeConnector {
    suspend fun connect(request: RealtimeConnectRequest): RealtimeTransport
}

data class RealtimeSettings(
    val model: String? = null,
    val voice: String = "cedar",
    val speed: Double = 1.0,
    val idleTimeoutSeconds: Int = 30,
    val maxSessionMinutes: Int = 10,
    val maxReconnects: Int = 2,
    val connectTimeoutSeconds: Int = 20,
)

/** Lo que la sesión de voz comunica al resto de la app (historial compartido con el chat). */
interface VoiceEventSink {
    fun userSaid(text: String)
    fun assistantSaid(text: String, cards: List<ResultCard>, runs: List<ToolRun>)
    fun notice(text: String)
    fun sessionEnded(reason: String)
}

/**
 * Controlador de una sesión de voz GPT Realtime. Todo evento (servidor, herramientas,
 * temporizador, órdenes) se procesa en serie en un único bucle: sin condiciones de carrera.
 *
 * Control de costos: cierre por inactividad, duración máxima, reconexiones limitadas.
 */
class RealtimeSessionController(
    private val connector: RealtimeConnector,
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val instructions: suspend () -> String,
    private val settings: () -> RealtimeSettings,
    private val working: WorkingContext,
    private val ui: UiEventSink,
    private val sink: VoiceEventSink,
    private val time: TimeSource,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VoiceState.OFF)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _usage = MutableStateFlow(RealtimeUsage())
    val usage: StateFlow<RealtimeUsage> = _usage.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _model = MutableStateFlow<String?>(null)
    val model: StateFlow<String?> = _model.asStateFlow()

    private sealed interface Input {
        data class Server(val generation: Int, val event: ServerEvent) : Input
        data class ToolDone(val generation: Int, val responseId: String?, val callId: String, val result: ToolResult, val run: ToolRun) : Input
        data object Tick : Input
        data class TransportLost(val generation: Int) : Input
        data object Interrupt : Input
        data class Stop(val reason: String) : Input
        data class UserText(val text: String) : Input
    }

    private class PendingResponse(val calls: MutableSet<String> = mutableSetOf(), var done: Boolean = false, var hadCalls: Boolean = false)

    private var inbox = Channel<Input>(Channel.UNLIMITED)
    private var transport: RealtimeTransport? = null
    /** Cada conexión nueva incrementa la generación; los eventos de conexiones viejas se ignoran. */
    private var generation = 0
    private var attachJobs: List<Job> = emptyList()
    private var sessionJob: Job? = null
    private var startedAtMs = 0L
    private var lastActivityMs = 0L
    private var reconnects = 0
    private var endAfterSpeech = false
    private var responding = false
    private val handledCalls = mutableSetOf<String>()
    private val pending = mutableMapOf<String, PendingResponse>()
    private val collectedCards = mutableListOf<ResultCard>()
    private val collectedRuns = mutableListOf<ToolRun>()
    private val transcriptBuffer = StringBuilder()

    val isActive: Boolean get() = sessionJob?.isActive == true

    /** Abre la sesión. Devuelve false (y deja el motivo en lastError) si no se pudo conectar. */
    suspend fun start(): Boolean {
        if (isActive) return true
        reconnects = 0
        _lastError.value = null
        _usage.value = RealtimeUsage()
        _state.value = VoiceState.CONNECTING
        val t = openTransport() ?: return fail(_lastError.value ?: "No se pudo iniciar la voz.")
        inbox = Channel(Channel.UNLIMITED)
        startedAtMs = time.now().toEpochMilli()
        lastActivityMs = startedAtMs
        attach(t)
        sessionJob = scope.launch { runLoop() }
        return true
    }

    fun interrupt() {
        inbox.trySend(Input.Interrupt)
    }

    fun stop(reason: String = "detenida por el usuario") {
        if (isActive) inbox.trySend(Input.Stop(reason))
    }

    /** Texto escrito durante una sesión de voz: va a la misma conversación. */
    fun sendText(text: String) {
        inbox.trySend(Input.UserText(text))
    }

    private suspend fun openTransport(): RealtimeTransport? {
        val s = settings()
        return try {
            val instr = instructions()
            withTimeout(s.connectTimeoutSeconds * 1000L) {
                connector.connect(RealtimeConnectRequest(s.model, s.voice, s.speed, instr, s.maxSessionMinutes))
            }
        } catch (e: TimeoutCancellationException) {
            _lastError.value = "Tiempo de conexión agotado con el servicio de voz."
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: "No se pudo conectar con el servicio de voz."
            null
        }
    }

    private fun attach(t: RealtimeTransport) {
        transport = t
        generation++
        val gen = generation
        resetTurnState()
        attachJobs = listOf(
            scope.launch { t.messages.collect { inbox.send(Input.Server(gen, RealtimeProtocol.parse(it))) } },
            scope.launch {
                t.state.first { it == TransportState.CLOSED || it == TransportState.FAILED }
                inbox.send(Input.TransportLost(gen))
            },
        )
    }

    private suspend fun detach() {
        attachJobs.forEach { it.cancel() }
        attachJobs = emptyList()
        val t = transport
        transport = null
        runCatching { t?.close() }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        _state.value = VoiceState.ERROR
        sink.notice("Voz no disponible: $message")
        scope.launch {
            delay(2500)
            if (_state.value == VoiceState.ERROR && !isActive) _state.value = VoiceState.OFF
        }
        return false
    }

    private suspend fun runLoop() {
        val ticker = scope.launch { while (isActive) { delay(1000); inbox.send(Input.Tick) } }
        try {
            for (input in inbox) {
                if (!handle(input)) break
            }
        } finally {
            ticker.cancel()
        }
    }

    /** Devuelve false para terminar la sesión. */
    private suspend fun handle(input: Input): Boolean {
        val t = transport
        when (input) {
            is Input.Server -> if (input.generation == generation && t != null) onServer(input.event, t)
            is Input.ToolDone -> if (input.generation == generation && t != null) onToolDone(input, t)
            Input.Tick -> onTick()
            Input.Interrupt -> if (t != null && (responding || _state.value == VoiceState.SPEAKING)) {
                t.send(RealtimeProtocol.responseCancel())
                t.send(RealtimeProtocol.outputAudioClear())
                responding = false
                _state.value = VoiceState.LISTENING
            }
            is Input.UserText -> if (t != null) {
                touch()
                t.send(RealtimeProtocol.userText(input.text))
                t.send(RealtimeProtocol.responseCreate())
                sink.userSaid(input.text)
            }
            is Input.Stop -> {
                finish(input.reason)
                return false
            }
            is Input.TransportLost -> if (input.generation == generation) return reconnectOrFail()
        }
        return true
    }

    private fun touch() {
        lastActivityMs = time.now().toEpochMilli()
    }

    private suspend fun onServer(e: ServerEvent, t: RealtimeTransport) {
        if (e !is ServerEvent.RateLimits && e !is ServerEvent.Other) touch()
        when (e) {
            is ServerEvent.SessionCreated -> {
                _model.value = e.model
                val tools = registry.definitionsFor(voice = true)
                t.send(RealtimeProtocol.sessionUpdate(instructions(), tools, settings().speed.takeIf { it != 1.0 }))
                _state.value = VoiceState.LISTENING
            }
            ServerEvent.SessionUpdated -> if (_state.value == VoiceState.CONNECTING) _state.value = VoiceState.LISTENING
            ServerEvent.SpeechStarted -> _state.value = VoiceState.USER_SPEAKING
            ServerEvent.SpeechStopped -> _state.value = VoiceState.THINKING
            is ServerEvent.ResponseCreated -> {
                responding = true
                transcriptBuffer.clear()
                if (_state.value != VoiceState.EXECUTING && _state.value != VoiceState.SPEAKING) _state.value = VoiceState.THINKING
            }
            is ServerEvent.TranscriptDelta -> transcriptBuffer.append(e.delta)
            is ServerEvent.TranscriptDone -> emitAssistant(e.transcript.ifBlank { transcriptBuffer.toString() })
            is ServerEvent.TextDone -> emitAssistant(e.text)
            ServerEvent.OutputAudioStarted -> _state.value = VoiceState.SPEAKING
            ServerEvent.OutputAudioStopped, ServerEvent.OutputAudioCleared -> {
                if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.LISTENING
                if (endAfterSpeech) inbox.trySend(Input.Stop("conversación terminada"))
            }
            is ServerEvent.FunctionCallArgsDone -> launchTool(e.responseId, e.call)
            is ServerEvent.ResponseDone -> onResponseDone(e, t)
            is ServerEvent.UserTranscript -> if (e.transcript.isNotBlank()) sink.userSaid(e.transcript.trim())
            is ServerEvent.Error -> onError(e)
            ServerEvent.UserTranscriptFailed, is ServerEvent.RateLimits, ServerEvent.IdleTimeoutTriggered, is ServerEvent.Other -> {}
        }
    }

    private fun emitAssistant(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() && collectedCards.isEmpty()) return
        sink.assistantSaid(clean, collectedCards.toList(), collectedRuns.toList())
        collectedCards.clear()
        collectedRuns.clear()
        transcriptBuffer.clear()
    }

    private fun launchTool(responseId: String?, call: FunctionCallItem) {
        if (call.callId.isBlank() || !handledCalls.add(call.callId)) return
        val key = responseId ?: "?"
        pending.getOrPut(key) { PendingResponse() }.apply { calls += call.callId; hadCalls = true }
        _state.value = VoiceState.EXECUTING
        if (call.name == "end_conversation") endAfterSpeech = true
        val gen = generation
        scope.launch {
            val started = time.now().toEpochMilli()
            val result = try {
                executor.execute(ToolCall(call.callId, call.name, call.arguments), ToolContext(RequestSource.VOICE, ui, working))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.error("tool_failed", e.message ?: "fallo")
            }
            val run = ToolRun(call.name, result.ok, result.message, time.now().toEpochMilli() - started, result.errorCode)
            inbox.send(Input.ToolDone(gen, responseId, call.callId, result, run))
        }
    }

    private fun onToolDone(d: Input.ToolDone, t: RealtimeTransport) {
        touch()
        collectedCards += d.result.cards
        collectedRuns += d.run
        t.send(RealtimeProtocol.functionCallOutput(d.callId, d.result.toModelOutput(5000)))
        val key = d.responseId ?: "?"
        val p = pending[key] ?: return
        p.calls.remove(d.callId)
        if (p.done && p.calls.isEmpty()) {
            pending.remove(key)
            requestFollowUp(t)
        }
    }

    private fun onResponseDone(e: ServerEvent.ResponseDone, t: RealtimeTransport) {
        responding = false
        e.usage?.let { _usage.value = _usage.value + it }
        e.functionCalls.forEach { launchTool(e.responseId, it) }
        val key = e.responseId ?: "?"
        val p = pending[key]
        if (p != null && p.hadCalls) {
            p.done = true
            if (p.calls.isEmpty()) {
                pending.remove(key)
                requestFollowUp(t)
            }
            return
        }
        when (e.status) {
            "failed" -> onError(ServerEvent.Error("response_failed", e.statusReason, e.errorMessage ?: "La respuesta falló."))
            "incomplete" -> if (e.statusReason == "max_output_tokens") sink.notice("Respuesta de voz recortada por el límite de longitud.")
        }
        if (!e.hadAudio && _state.value != VoiceState.SPEAKING) _state.value = VoiceState.LISTENING
        if (!e.hadAudio && endAfterSpeech) inbox.trySend(Input.Stop("conversación terminada"))
    }

    private fun requestFollowUp(t: RealtimeTransport) {
        _state.value = VoiceState.THINKING
        t.send(RealtimeProtocol.responseCreate())
    }

    private fun onError(e: ServerEvent.Error) {
        if (e.code in BENIGN_ERRORS) return
        _lastError.value = e.message
        sink.notice("Error de voz: ${e.message}")
        if (e.code == "session_expired") inbox.trySend(Input.Stop("la sesión expiró"))
    }

    private fun onTick() {
        val now = time.now().toEpochMilli()
        val s = settings()
        _usage.value = _usage.value.copy(sessionSeconds = (now - startedAtMs) / 1000)
        val quiet = _state.value == VoiceState.LISTENING && pending.isEmpty() && !responding
        if (quiet && now - lastActivityMs > s.idleTimeoutSeconds * 1000L) {
            inbox.trySend(Input.Stop("inactividad"))
        } else if (now - startedAtMs > s.maxSessionMinutes * 60_000L) {
            sink.notice("Sesión de voz cerrada al alcanzar la duración máxima (${s.maxSessionMinutes} min).")
            inbox.trySend(Input.Stop("duración máxima"))
        }
    }

    private suspend fun reconnectOrFail(): Boolean {
        detach()
        val max = settings().maxReconnects
        while (reconnects < max) {
            reconnects++
            sink.notice("Conexión de voz interrumpida; reintentando ($reconnects/$max)…")
            _state.value = VoiceState.CONNECTING
            delay(1000L * reconnects * reconnects)
            val t = openTransport()
            if (t != null) {
                attach(t)
                sink.notice("Conexión de voz restablecida. El contexto de voz anterior se reinició.")
                return true
            }
        }
        _lastError.value = "Se perdió la conexión de voz."
        sink.notice("Se perdió la conexión de voz y no se pudo restablecer.")
        _state.value = VoiceState.ERROR
        sink.sessionEnded("conexión perdida")
        scope.launch { delay(2500); if (_state.value == VoiceState.ERROR) _state.value = VoiceState.OFF }
        return false
    }

    private fun resetTurnState() {
        handledCalls.clear(); pending.clear(); collectedCards.clear(); collectedRuns.clear(); transcriptBuffer.clear()
        responding = false; endAfterSpeech = false
    }

    private suspend fun finish(reason: String) {
        emitAssistant(transcriptBuffer.toString())
        detach()
        _state.value = VoiceState.OFF
        sink.sessionEnded(reason)
    }

    companion object {
        private val BENIGN_ERRORS = setOf("response_cancel_not_active", "conversation_already_has_active_response")
    }
}
