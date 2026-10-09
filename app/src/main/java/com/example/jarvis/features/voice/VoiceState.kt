package com.example.jarvis.features.voice

import android.util.Log

enum class VoiceState {
    IDLE,
    WAKE_DETECTED,
    TTS_CONFIRMING,
    TTS_CONFIRMED,
    STT_LISTENING,
    STT_RESULT,
    BRAIN_PROCESSING,
    TTS_RESPONSE;

    fun transitionTo(next: VoiceState): VoiceState {
        Log.i("JARVIS", "[JARVIS][VOICE_STATE] $this -> $next")
        return next
    }
}
