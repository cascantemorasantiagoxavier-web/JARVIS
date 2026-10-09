package com.example.jarvis.ai.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MockRealtimeClient : RealtimeClient {
    private val _events = MutableSharedFlow<RealtimeEvent>()
    var currentState: RealtimeConnectionState = RealtimeConnectionState.IDLE
        private set

    override suspend fun connect(session: RealtimeSessionConfig) {
        currentState = RealtimeConnectionState.CONNECTING
        _events.emit(RealtimeEvent.StateChanged(currentState))
        currentState = RealtimeConnectionState.CONNECTED
        _events.emit(RealtimeEvent.StateChanged(currentState))
    }

    override suspend fun disconnect() {
        currentState = RealtimeConnectionState.DISCONNECTED
        _events.emit(RealtimeEvent.StateChanged(currentState))
    }

    override suspend fun sendText(text: String) {
        currentState = RealtimeConnectionState.THINKING
        _events.emit(RealtimeEvent.StateChanged(currentState))
        _events.emit(RealtimeEvent.TextReceived("Response to: $text"))
        currentState = RealtimeConnectionState.SPEAKING
        _events.emit(RealtimeEvent.StateChanged(currentState))
    }

    override suspend fun sendAudio(audio: ByteArray) {
        currentState = RealtimeConnectionState.LISTENING
        _events.emit(RealtimeEvent.StateChanged(currentState))
    }

    override suspend fun commitAndCreateResponse() {}

    override fun events(): Flow<RealtimeEvent> {
        return _events.asSharedFlow()
    }
}
