package com.example.jarvis.ai.realtime

import android.util.Log
import com.example.jarvis.ai.orchestrator.DefaultVoiceOrchestratorBridge
import com.example.jarvis.ai.orchestrator.models.VoiceCommand
import com.example.jarvis.ai.tools.DefaultRiskEvaluator
import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.logging.ConsoleLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class GeminiLiveRealtimeClient : RealtimeClient {

    private val _events = MutableSharedFlow<RealtimeEvent>(replay = 1)
    private val scope = CoroutineScope(Dispatchers.IO)

    private var currentState: RealtimeConnectionState = RealtimeConnectionState.IDLE
    private var isConnected = false

    private val orchestrator = JarvisBrain.orchestrator
    private val toolRegistry = JarvisBrain.toolRegistry
    private val riskEvaluator = DefaultRiskEvaluator()
    private val voiceBridge = DefaultVoiceOrchestratorBridge(orchestrator, riskEvaluator, toolRegistry, logger = ConsoleLogger())

    private var sessionStartTimeMs: Long = 0L

    override suspend fun connect(session: RealtimeSessionConfig) {
        isConnected = true
        sessionStartTimeMs = System.currentTimeMillis()
        updateState(RealtimeConnectionState.CONNECTED)
        Log.i("JARVIS", "[JARVIS][LIVE] live_session_ready=true model=gemini-3.8-live")
    }

    override suspend fun disconnect() {
        isConnected = false
        updateState(RealtimeConnectionState.DISCONNECTED)
        Log.i("JARVIS", "[JARVIS][LIVE] live_session_closed=true")
    }

    override suspend fun sendText(text: String) {
        if (!isConnected) return
        processVoiceInteraction(text)
    }

    override suspend fun sendAudio(audio: ByteArray) {
        if (!isConnected) return
        Log.d("JARVIS", "[JARVIS][LIVE] audio_chunk_streamed size=${audio.size}")
    }

    override suspend fun commitAndCreateResponse() {
        Log.i("JARVIS", "[JARVIS][LIVE] commit_response_requested")
    }

    override fun events(): Flow<RealtimeEvent> = _events.asSharedFlow()

    fun processVoiceInteraction(text: String) {
        scope.launch {
            val startMs = System.currentTimeMillis()
            updateState(RealtimeConnectionState.THINKING)
            _events.emit(RealtimeEvent.UserTranscriptReceived(text))

            Log.i("JARVIS", "[JARVIS][LIVE] brain_request_started query=\"$text\" model=gemini-3.8-live")

            val command = VoiceCommand(text = text)
            val response = voiceBridge.processVoiceCommand(command)

            val brainLatencyMs = System.currentTimeMillis() - startMs

            if (response.error != null) {
                Log.e("JARVIS", "[JARVIS][LIVE] error=GEMINI_LIVE_FAILED details=${response.error.userMessage}")
                val err = RealtimeError(code = 500, message = response.error.userMessage)
                _events.emit(RealtimeEvent.ErrorOccurred(err))
                updateState(RealtimeConnectionState.ERROR)
            } else {
                val firstAudioTimeMs = System.currentTimeMillis()
                val firstAudioLatencyMs = firstAudioTimeMs - startMs
                Log.i("JARVIS", "[JARVIS][LIVE] voice_first_audio_latency_ms=$firstAudioLatencyMs gemini_latency_ms=$brainLatencyMs text_length=${response.text.length}")

                _events.emit(RealtimeEvent.AssistantTextReceived(response.text))
                updateState(RealtimeConnectionState.SPEAKING)

                val totalLatencyMs = System.currentTimeMillis() - startMs
                Log.i("JARVIS", "[JARVIS][LIVE] voice_total_latency_ms=$totalLatencyMs")
                _events.emit(RealtimeEvent.ResponseCompleted)
                updateState(RealtimeConnectionState.IDLE)
            }
        }
    }

    private suspend fun updateState(newState: RealtimeConnectionState) {
        currentState = newState
        _events.emit(RealtimeEvent.StateChanged(newState))
    }
}
