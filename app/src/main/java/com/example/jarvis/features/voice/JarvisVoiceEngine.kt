package com.example.jarvis.features.voice

interface JarvisVoiceEngine {
    fun speak(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}, onError: (String) -> Unit = {})
    fun stop()
    fun shutdown()
}
