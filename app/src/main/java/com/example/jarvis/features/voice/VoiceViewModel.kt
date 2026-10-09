package com.example.jarvis.features.voice

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jarvis.ai.realtime.*
import com.example.jarvis.ai.wakeword.WakeWordService
import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.di.JarvisRequest
import com.example.jarvis.core.di.RequestSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceUiState(
    val voiceState: RealtimeConnectionState = RealtimeConnectionState.IDLE,
    val statusText: String = "Cerebro Híbrido Gemini conectado (Voz lista)",
    val userQuery: String = "",
    val audioLevel: Float = 0f,
    val hasMicPermission: Boolean = false
)

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val voiceEngine: JarvisVoiceEngine = CompositeJarvisVoiceEngine(getApplication<Application>().applicationContext)

    private val sttRecognizer = AndroidSpeechRecognizer(
        context = getApplication<Application>().applicationContext,
        onResult = { transcribedText ->
            Log.i("JARVIS", "[JARVIS][VOICE] transcript_received=\"$transcribedText\"")
            handleTranscribedSpeech(transcribedText)
        },
        onError = { errorCode ->
            Log.e("JARVIS", "[JARVIS][VOICE] error=$errorCode")
            _uiState.update { it.copy(voiceState = RealtimeConnectionState.ERROR, statusText = errorCode, audioLevel = 0f) }
        },
        onReady = {
            Log.i("JARVIS", "[JARVIS][VOICE] microphone_started=true")
            _uiState.update { it.copy(voiceState = RealtimeConnectionState.LISTENING, statusText = "JARVIS escuchando tu voz...") }
        },
        onAudioLevel = { level ->
            _uiState.update { it.copy(audioLevel = level) }
        }
    )

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_WAKE_WORD_DETECTED) {
                Log.i("JARVIS", "[JARVIS][WAKE] WAKE_WORD_DETECTED: Wake Word triggered, starting STT listening automatically")
                _uiState.update { it.copy(statusText = "¡JARVIS detectado! Escuchando comando...") }
                sttRecognizer.startListening()
            }
        }
    }

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    init {
        checkPermission()
        registerWakeWordReceiver()
    }

    private fun registerWakeWordReceiver() {
        try {
            val filter = IntentFilter(WakeWordService.ACTION_WAKE_WORD_DETECTED)
            val app = getApplication<Application>().applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(wakeWordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(wakeWordReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "Failed to register wakeWordReceiver: ${e.message}")
        }
    }

    fun checkPermission() {
        val app = getApplication<Application>().applicationContext
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        _uiState.update { it.copy(hasMicPermission = granted) }
    }

    fun toggleListening() {
        checkPermission()
        if (!_uiState.value.hasMicPermission) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=MIC_PERMISSION_DENIED")
            _uiState.update { it.copy(voiceState = RealtimeConnectionState.ERROR, statusText = "MIC_PERMISSION_DENIED: Se requiere permiso de micrófono.", audioLevel = 0f) }
            return
        }

        val current = _uiState.value.voiceState
        if (current == RealtimeConnectionState.SPEAKING) {
            voiceEngine.stop()
            sttRecognizer.startListening()
        } else if (current == RealtimeConnectionState.IDLE || current == RealtimeConnectionState.DISCONNECTED || current == RealtimeConnectionState.ERROR) {
            voiceEngine.stop()
            sttRecognizer.startListening()
        } else {
            sttRecognizer.stopListening()
            voiceEngine.stop()
            _uiState.update { it.copy(voiceState = RealtimeConnectionState.IDLE, statusText = "Escucha detenida", audioLevel = 0f) }
        }
    }

    private fun handleTranscribedSpeech(text: String) {
        viewModelScope.launch {
            Log.i("JARVIS", "[JARVIS][VOICE] brain_request_started query=\"$text\"")
            _uiState.update {
                it.copy(
                    voiceState = RealtimeConnectionState.THINKING,
                    userQuery = text,
                    statusText = "Procesando en JarvisBrain...",
                    audioLevel = 0f
                )
            }

            val jarvisReq = JarvisRequest(text = text, source = RequestSource.VOICE)
            val response = JarvisBrain.processRequest(jarvisReq)

            Log.i("JARVIS", "[JARVIS][VOICE] brain_response_received text_length=${response.text.length}")
            _uiState.update { it.copy(statusText = response.text, audioLevel = 0f) }

            voiceEngine.speak(
                text = response.text,
                onStart = {
                    Log.i("JARVIS", "[JARVIS][VOICE] tts_started=true")
                    _uiState.update { it.copy(voiceState = RealtimeConnectionState.SPEAKING, audioLevel = 0f) }
                },
                onDone = {
                    Log.i("JARVIS", "[JARVIS][VOICE] tts_completed=true")
                    _uiState.update { it.copy(voiceState = RealtimeConnectionState.IDLE, statusText = "Respuesta completada - Listo", audioLevel = 0f) }
                },
                onError = { errCode ->
                    Log.e("JARVIS", "[JARVIS][VOICE] error=$errCode")
                    _uiState.update { it.copy(voiceState = RealtimeConnectionState.ERROR, statusText = "Error de Voz: $errCode", audioLevel = 0f) }
                }
            )
        }
    }

    fun processCommand(text: String) {
        handleTranscribedSpeech(text)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().applicationContext.unregisterReceiver(wakeWordReceiver)
        } catch (_: Exception) {}
        sttRecognizer.destroy()
        voiceEngine.shutdown()
    }
}
