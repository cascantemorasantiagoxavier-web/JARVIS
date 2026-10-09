package com.example.jarvis.ai.realtime

sealed interface RealtimeEvent {
    data class StateChanged(val state: RealtimeConnectionState) : RealtimeEvent
    data class TextReceived(val text: String) : RealtimeEvent
    data class UserTranscriptReceived(val text: String) : RealtimeEvent
    data class AssistantTextReceived(val text: String) : RealtimeEvent
    data class AudioReceived(val chunk: RealtimeAudioChunk) : RealtimeEvent
    object UserSpeechStarted : RealtimeEvent
    object ResponseCompleted : RealtimeEvent
    data class ErrorOccurred(val error: RealtimeError) : RealtimeEvent
}
