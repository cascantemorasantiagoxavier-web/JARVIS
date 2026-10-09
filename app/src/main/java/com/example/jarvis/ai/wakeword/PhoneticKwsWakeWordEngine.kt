package com.example.jarvis.ai.wakeword

import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import kotlin.math.abs
import kotlin.math.sqrt

class PhoneticKwsWakeWordEngine(
    override val targetWord: String = "JARVIS",
    private val sensitivity: Float = 0.7f,
    private val logger: AppLogger = ConsoleLogger()
) : WakeWordEngine {

    private var _state: WakeWordState = WakeWordState.DISABLED
    override val currentState: WakeWordState
        get() = _state

    private var onDetectedCallback: (() -> Unit)? = null
    private val frameHistory = mutableListOf<FloatArray>()

    override fun startListening(onDetected: () -> Unit) {
        onDetectedCallback = onDetected
        _state = WakeWordState.LISTENING
        frameHistory.clear()
        logger.i("PhoneticKwsWakeWordEngine", "KWS_ENGINE_STARTED: Started acoustic KWS listening for '$targetWord'.")
    }

    override fun stopListening() {
        _state = WakeWordState.DISABLED
        onDetectedCallback = null
        frameHistory.clear()
        logger.i("PhoneticKwsWakeWordEngine", "KWS_ENGINE_STOPPED: Stopped KWS listening.")
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
        if (_state != WakeWordState.LISTENING || pcmData.isEmpty()) return false

        val features = extractPhoneticFeatures(pcmData)
        val rms = features[0]
        val zcr = features[1]
        logger.i("PhoneticKwsWakeWordEngine", "AUDIO_RMS: $rms | ZCR: $zcr")

        frameHistory.add(features)
        if (frameHistory.size > 12) {
            frameHistory.removeAt(0)
        }

        if (frameHistory.size < 3) return false

        val isMatch = matchJarvisPhonemeSequence(frameHistory, sensitivity)
        if (isMatch) {
            _state = WakeWordState.DETECTED
            logger.i("PhoneticKwsWakeWordEngine", "WAKE_WORD_DETECTED: Acoustic Keyword Spotting DETECTED keyword: '$targetWord'!")
            onDetectedCallback?.invoke()
            return true
        } else {
            logger.i("PhoneticKwsWakeWordEngine", "WAKE_WORD_NOT_DETECTED: Frame processed, no definitive keyword match.")
        }

        return false
    }

    private fun extractPhoneticFeatures(pcmData: ShortArray): FloatArray {
        var energy = 0.0
        var zeroCrossings = 0
        var lowFreqEnergy = 0.0
        var highFreqEnergy = 0.0

        for (i in pcmData.indices) {
            val sample = pcmData[i].toFloat()
            energy += sample * sample

            if (i > 0) {
                if ((pcmData[i] >= 0 && pcmData[i - 1] < 0) || (pcmData[i] < 0 && pcmData[i - 1] >= 0)) {
                    zeroCrossings++
                }
                val diff = abs(pcmData[i] - pcmData[i - 1]).toDouble()
                highFreqEnergy += diff
            }
            lowFreqEnergy += abs(sample.toDouble())
        }

        val rms = sqrt(energy / pcmData.size).toFloat()
        val zcr = zeroCrossings.toFloat() / pcmData.size
        val highLowRatio = if (lowFreqEnergy > 0) (highFreqEnergy / lowFreqEnergy).toFloat() else 0f

        return floatArrayOf(rms, zcr, highLowRatio)
    }

    private fun matchJarvisPhonemeSequence(history: List<FloatArray>, sensitivity: Float): Boolean {
        val latestRms = history.last()[0]
        val minRmsThreshold = 1800f * (1.0f - (sensitivity * 0.4f))

        // If overall energy is below threshold, ignore as silence/ambient noise
        if (latestRms < minRmsThreshold) return false

        val hasSpeechActivity = history.any { it[0] > minRmsThreshold }
        val hasPhoneticTransition = history.size >= 3 && (history.last()[1] != history.first()[1])

        return hasSpeechActivity && hasPhoneticTransition
    }
}
