package com.example.jarvis.ai.realtime

enum class RealtimeConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    EXECUTING,
    SPEAKING,
    INTERRUPTED,
    ERROR,
    DISCONNECTED
}
