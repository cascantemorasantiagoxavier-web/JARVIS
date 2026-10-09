package com.example.jarvis.ai.wakeword

import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger

interface WakeWordEngine {
    val targetWord: String
    val currentState: WakeWordState
    fun startListening(onDetected: () -> Unit)
    fun stopListening()
    fun pause()
    fun resume()
    fun release()
    fun processAudioFrame(pcmData: ShortArray): Boolean
}

class LocalWakeWordEngine(
    override val targetWord: String = "JARVIS",
    private val sensitivity: Float = 0.8f,
    private val logger: AppLogger = ConsoleLogger()
) : WakeWordEngine {

    private var _state: WakeWordState = WakeWordState.DISABLED
    override val currentState: WakeWordState
        get() = _state

    private var onDetectedCallback: (() -> Unit)? = null

    override fun startListening(onDetected: () -> Unit) {
        onDetectedCallback = onDetected
        _state = WakeWordState.LISTENING
        logger.i("LocalWakeWordEngine", "Started offline listening for '$targetWord'.")
    }

    override fun stopListening() {
        _state = WakeWordState.DISABLED
        onDetectedCallback = null
        logger.i("LocalWakeWordEngine", "Stopped listening for '$targetWord'.")
    }

    override fun pause() {
        if (_state == WakeWordState.LISTENING) {
            _state = WakeWordState.PAUSED
        }
    }

    override fun resume() {
        if (_state == WakeWordState.PAUSED) {
            _state = WakeWordState.LISTENING
        }
    }

    override fun release() {
        stopListening()
    }

    override fun processAudioFrame(pcmData: ShortArray): Boolean {
        if (_state != WakeWordState.LISTENING) return false

        var energy = 0.0
        for (sample in pcmData) {
            energy += sample * sample
        }
        val rms = Math.sqrt(energy / pcmData.size)

        if (rms > 8000 * sensitivity) {
            _state = WakeWordState.DETECTED
            logger.i("LocalWakeWordEngine", "Offline wake word '$targetWord' DETECTED!")
            onDetectedCallback?.invoke()
            return true
        }
        return false
    }
}
