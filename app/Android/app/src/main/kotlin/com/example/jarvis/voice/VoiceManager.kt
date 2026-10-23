package com.example.jarvis.voice

import android.Manifest
import android.content.Context
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.Role
import com.example.jarvis.core.model.ToolRun
import com.example.jarvis.core.orchestrator.JarvisOrchestrator
import com.example.jarvis.core.realtime.RealtimeSessionController
import com.example.jarvis.core.realtime.VoiceEventSink
import com.example.jarvis.core.realtime.VoiceState
import com.example.jarvis.data.settings.SettingsRepository
import com.example.jarvis.data.settings.StoredUsage
import com.example.jarvis.data.settings.VoiceEngine
import com.example.jarvis.device.PermissionBroker
import com.example.jarvis.voice.wakeword.WakeWordEngine
import com.example.jarvis.voice.wakeword.WakeWordEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class WakeAvailability(val available: Boolean, val reason: String?)

/**
 * Coordina las tres piezas de voz sin que compitan por el micrófono:
 *  1. Palabra clave local ("JARVIS") → 2. sesión GPT Realtime (o voz local de respaldo) →
 *  3. al terminar la sesión, vuelve a esperar la palabra clave si está activada.
 * Chat y voz comparten orquestador, herramientas, memoria e historial.
 */
class VoiceManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val realtime: RealtimeSessionController,
    private val orchestrator: () -> JarvisOrchestrator,
    private val local: LocalSpeech,
    private val routing: AudioRouting,
    val levels: AudioLevels,
    private val permissions: PermissionBroker,
    private val realtimeReady: () -> Boolean,
) : VoiceEventSink {

    private val _localState = MutableStateFlow(VoiceState.OFF)
    private val _wakeListening = MutableStateFlow(false)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Estado de voz combinado que ve la interfaz. */
    val state: StateFlow<VoiceState> = combine(realtime.state, _localState, _wakeListening) { rt, loc, wake ->
        when {
            rt != VoiceState.OFF -> rt
            loc != VoiceState.OFF -> loc
            wake -> VoiceState.WAKE_WORD
            else -> VoiceState.OFF
        }
    }.stateIn(scope, SharingStarted.Eagerly, VoiceState.OFF)

    private var wakeEngine: WakeWordEngine? = null
    private var engineSensitivity = Float.NaN
    private var localJob: Job? = null
    @Volatile private var serviceRunning = false

    val wakeAvailability: WakeAvailability
        get() = engine().let { WakeAvailability(it.isAvailable, it.unavailableReason) }

    /** Motor de palabra clave; se recrea si cambió la sensibilidad y no está escuchando. */
    private fun engine(): WakeWordEngine {
        val sensitivity = settings.current.wakeSensitivity
        wakeEngine?.let { e -> if (sensitivity == engineSensitivity || _wakeListening.value) return e else e.stop() }
        return WakeWordEngineFactory.create(context, sensitivity).also { wakeEngine = it; engineSensitivity = sensitivity }
    }

    val conversationActive: Boolean get() = realtime.isActive || localJob?.isActive == true

    /** Botón de micrófono: inicia o termina la conversación. */
    fun toggleConversation() {
        if (conversationActive) stopConversation("detenida por el usuario") else scope.launch { startConversation() }
    }

    suspend fun startConversation(fromWakeWord: Boolean = false) {
        if (conversationActive) return
        if (!permissions.isGranted(Manifest.permission.RECORD_AUDIO) && !permissions.request(Manifest.permission.RECORD_AUDIO)) {
            notice("Necesito el permiso de micrófono para escucharte. Puedes concederlo en Ajustes del sistema.")
            return
        }
        // Liberar el micrófono de la palabra clave antes de abrir la conversación
        wakeEngine?.stop()
        _wakeListening.value = false
        ensureService()

        val wantRealtime = settings.current.voiceEngine == VoiceEngine.REALTIME
        if (wantRealtime && realtimeReady()) {
            routing.start()
            if (realtime.start()) return
            routing.stop()
            val why = realtime.lastError.value ?: "error desconocido"
            notice("La voz GPT Realtime no está disponible ($why). Uso la voz local del teléfono.")
        } else if (wantRealtime) {
            notice("La voz GPT Realtime necesita el servidor configurado en Ajustes. Uso la voz local del teléfono.")
        }
        startLocal(greet = fromWakeWord)
    }

    fun stopConversation(reason: String) {
        if (realtime.isActive) realtime.stop(reason)
        localJob?.cancel()
        local.cancelListening()
        local.stopSpeaking()
    }

    /** Interrumpe a JARVIS mientras habla (la sesión sigue escuchando). */
    fun interrupt() {
        if (realtime.isActive) realtime.interrupt() else local.stopSpeaking()
    }

    private fun startLocal(greet: Boolean) {
        localJob = scope.launch {
            try {
                if (greet) speakLocal("¿Sí, jefe?")
                do {
                    _localState.value = VoiceState.LISTENING
                    when (val heard = local.listenOnce()) {
                        is ListenResult.Heard -> {
                            _localState.value = VoiceState.THINKING
                            val job = orchestrator().submit(heard.text, com.example.jarvis.core.model.RequestSource.VOICE)
                            job?.join()
                            val reply = orchestrator().entries.value.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty()
                            if (reply.isNotBlank()) speakLocal(reply)
                        }
                        ListenResult.Silence -> break
                        is ListenResult.Failed -> { notice(heard.reason); break }
                    }
                } while (settings.current.localContinuous)
            } finally {
                _localState.value = VoiceState.OFF
                onConversationFinished()
            }
        }
    }

    private suspend fun speakLocal(text: String) {
        _localState.value = VoiceState.SPEAKING
        val s = settings.current
        local.speak(text, s.ttsVoiceName, s.ttsRate, s.ttsPitch)
    }

    // ---------- palabra clave ----------

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        if (enabled) {
            val e = engine()
            if (!e.isAvailable) { notice(e.unavailableReason ?: "Palabra clave no disponible."); return }
            if (!permissions.request(Manifest.permission.RECORD_AUDIO)) { notice("Sin permiso de micrófono no puedo esperar la palabra clave."); return }
            if (android.os.Build.VERSION.SDK_INT >= 33) permissions.request(Manifest.permission.POST_NOTIFICATIONS)
        }
        settings.update { it.copy(wakeWordEnabled = enabled) }
        if (enabled) {
            ensureService()
            startWakeListening()
        } else {
            wakeEngine?.stop()
            _wakeListening.value = false
            if (!conversationActive) VoiceForegroundService.stop(context)
        }
    }

    /**
     * Al abrir la app: si el usuario dejó activada la palabra clave (p. ej. tras reiniciar el
     * teléfono), se reanuda. Android solo permite abrir el micrófono en segundo plano si el
     * servicio arrancó con la app visible, por eso se llama desde MainActivity.onStart.
     */
    suspend fun restoreWakeWord() {
        val s = settings.awaitLoaded()
        if (!s.wakeWordEnabled || serviceRunning || conversationActive) return
        if (!engine().isAvailable || !permissions.isGranted(Manifest.permission.RECORD_AUDIO)) return
        ensureService()
        startWakeListening()
    }

    private fun startWakeListening() {
        if (!settings.current.wakeWordEnabled || conversationActive) return
        val ok = engine().start(
            onDetected = { scope.launch { _wakeListening.value = false; startConversation(fromWakeWord = true) } },
            onError = { msg -> _wakeListening.value = false; notice(msg) },
        )
        _wakeListening.value = ok
        VoiceForegroundService.update(context, if (ok) VoiceForegroundService.Mode.WAKE else VoiceForegroundService.Mode.SESSION)
    }

    private fun onConversationFinished() {
        routing.stop()
        if (settings.current.wakeWordEnabled && serviceRunning) {
            startWakeListening()
        } else if (!settings.current.wakeWordEnabled) {
            VoiceForegroundService.stop(context)
        }
    }

    // ---------- servicio en primer plano ----------

    private fun ensureService() {
        if (!serviceRunning) VoiceForegroundService.start(context, if (settings.current.wakeWordEnabled && !conversationActive) VoiceForegroundService.Mode.WAKE else VoiceForegroundService.Mode.SESSION)
        else VoiceForegroundService.update(context, VoiceForegroundService.Mode.SESSION)
    }

    fun onServiceRunning() {
        serviceRunning = true
        if (!conversationActive) startWakeListening()
    }

    fun onServiceStopped() {
        serviceRunning = false
        wakeEngine?.stop()
        _wakeListening.value = false
    }

    fun onServiceFailed(reason: String?) {
        serviceRunning = false
        notice("Android no permitió mantener el micrófono activo en segundo plano${reason?.let { " ($it)" } ?: ""}. Abre JARVIS y vuelve a activarlo.")
    }

    /** Acción "Detener" de la notificación. */
    fun stopEverythingFromNotification() {
        stopConversation("detenida desde la notificación")
        scope.launch { setWakeWordEnabled(false) }
    }

    // ---------- VoiceEventSink (sesión Realtime) ----------

    override fun userSaid(text: String) = orchestrator().recordVoice(Role.USER, text)

    override fun assistantSaid(text: String, cards: List<ResultCard>, runs: List<ToolRun>) =
        orchestrator().recordVoice(Role.ASSISTANT, text, cards, runs)

    override fun notice(text: String) {
        _notices.tryEmit(text)
        orchestrator().recordSystem(text)
    }

    override fun sessionEnded(reason: String) {
        val usage = realtime.usage.value
        scope.launch {
            val today = LocalDate.now().toString()
            settings.update { s ->
                val base = if (s.usageDay == today) s.usageToday else StoredUsage()
                s.copy(usageDay = today, usageToday = base + usage)
            }
        }
        if (reason != "detenida por el usuario") orchestrator().recordSystem("Sesión de voz cerrada: $reason.")
        onConversationFinished()
    }
}
