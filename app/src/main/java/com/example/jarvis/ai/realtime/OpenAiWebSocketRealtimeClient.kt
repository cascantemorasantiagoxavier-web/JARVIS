package com.example.jarvis.ai.realtime

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject

class OpenAiWebSocketRealtimeClient : RealtimeClient {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val _events = MutableSharedFlow<RealtimeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    var currentState: RealtimeConnectionState = RealtimeConnectionState.IDLE
        private set

    override suspend fun connect(session: RealtimeSessionConfig) {
        currentState = RealtimeConnectionState.CONNECTING
        _events.emit(RealtimeEvent.StateChanged(currentState))

        val model = session.model.ifBlank { "gpt-realtime-2.1" }
        val url = "wss://api.openai.com/v1/realtime?model=$model"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${session.token}")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                currentState = RealtimeConnectionState.CONNECTED
                _events.tryEmit(RealtimeEvent.StateChanged(currentState))

                val sessionUpdate = "{\"type\":\"session.update\",\"session\":{\"turn_detection\":{\"type\":\"server_vad\"},\"input_audio_format\":\"pcm16\",\"output_audio_format\":\"pcm16\"}}"
                ws.send(sessionUpdate)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    when (type) {
                        "response.audio.delta" -> {
                            currentState = RealtimeConnectionState.SPEAKING
                            _events.tryEmit(RealtimeEvent.StateChanged(currentState))
                            val delta = json.optString("delta", "")
                            if (delta.isNotBlank()) {
                                val audioBytes = Base64.decode(delta, Base64.DEFAULT)
                                _events.tryEmit(RealtimeEvent.AudioReceived(RealtimeAudioChunk(audioBytes)))
                            }
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            val transcript = json.optString("transcript", "")
                            if (transcript.isNotBlank()) {
                                _events.tryEmit(RealtimeEvent.UserTranscriptReceived(transcript))
                            }
                        }
                        "response.audio_transcript.delta", "response.text.delta" -> {
                            val delta = json.optString("delta", "")
                            if (delta.isNotBlank()) {
                                _events.tryEmit(RealtimeEvent.AssistantTextReceived(delta))
                            }
                        }
                        "input_audio_buffer.speech_started" -> {
                            currentState = RealtimeConnectionState.LISTENING
                            _events.tryEmit(RealtimeEvent.StateChanged(currentState))
                            _events.tryEmit(RealtimeEvent.UserSpeechStarted)
                        }
                        "response.done" -> {
                            currentState = RealtimeConnectionState.IDLE
                            _events.tryEmit(RealtimeEvent.StateChanged(currentState))
                            _events.tryEmit(RealtimeEvent.ResponseCompleted)
                        }
                        "error" -> {
                            val errObj = json.optJSONObject("error")
                            val message = errObj?.optString("message") ?: "Realtime API error"
                            currentState = RealtimeConnectionState.ERROR
                            _events.tryEmit(RealtimeEvent.StateChanged(currentState))
                            _events.tryEmit(RealtimeEvent.ErrorOccurred(RealtimeError(-1, message)))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("JARVIS", "[JARVIS][REALTIME] Error parsing WebSocket message: ${e.message}", e)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                currentState = RealtimeConnectionState.DISCONNECTED
                _events.tryEmit(RealtimeEvent.StateChanged(currentState))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                currentState = RealtimeConnectionState.ERROR
                _events.tryEmit(RealtimeEvent.StateChanged(currentState))
                _events.tryEmit(RealtimeEvent.ErrorOccurred(RealtimeError(-1, t.message ?: "WebSocket failure", t)))
            }
        })
    }

    override suspend fun disconnect() {
        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (_: Exception) {}
        webSocket = null
        currentState = RealtimeConnectionState.DISCONNECTED
        _events.emit(RealtimeEvent.StateChanged(currentState))
    }

    override suspend fun sendText(text: String) {
        if (webSocket == null || currentState == RealtimeConnectionState.DISCONNECTED || currentState == RealtimeConnectionState.ERROR) {
            Log.w("JARVIS", "[JARVIS][REALTIME] Cannot send text: WebSocket disconnected")
            return
        }
        try {
            val jsonEvent = "{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"$text\"}]}}"
            webSocket?.send(jsonEvent)
            webSocket?.send("{\"type\":\"response.create\"}")
            currentState = RealtimeConnectionState.THINKING
            _events.emit(RealtimeEvent.StateChanged(currentState))
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][REALTIME] Exception in sendText: ${e.message}", e)
            currentState = RealtimeConnectionState.ERROR
            _events.emit(RealtimeEvent.StateChanged(currentState))
        }
    }

    override suspend fun sendAudio(audio: ByteArray) {
        if (webSocket == null || currentState == RealtimeConnectionState.DISCONNECTED || currentState == RealtimeConnectionState.ERROR) {
            return
        }
        try {
            val base64Audio = Base64.encodeToString(audio, Base64.NO_WRAP)
            val jsonEvent = "{\"type\":\"input_audio_buffer.append\",\"audio\":\"$base64Audio\"}"
            val sent = webSocket?.send(jsonEvent) ?: false
            if (!sent) {
                Log.e("JARVIS", "[JARVIS][REALTIME] Broken pipe on sendAudio: socket rejected frame")
                currentState = RealtimeConnectionState.ERROR
                _events.tryEmit(RealtimeEvent.StateChanged(currentState))
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][REALTIME] Exception in sendAudio: ${e.message}", e)
            currentState = RealtimeConnectionState.ERROR
            _events.tryEmit(RealtimeEvent.StateChanged(currentState))
        }
    }

    override suspend fun commitAndCreateResponse() {
        if (webSocket == null || currentState == RealtimeConnectionState.DISCONNECTED || currentState == RealtimeConnectionState.ERROR) {
            return
        }
        try {
            webSocket?.send("{\"type\":\"input_audio_buffer.commit\"}")
            webSocket?.send("{\"type\":\"response.create\"}")
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][REALTIME] Exception in commitAndCreateResponse: ${e.message}", e)
        }
    }

    override fun events(): Flow<RealtimeEvent> = _events.asSharedFlow()
}
