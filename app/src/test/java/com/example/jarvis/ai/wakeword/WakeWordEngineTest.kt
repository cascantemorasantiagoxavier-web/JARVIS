package com.example.jarvis.ai.wakeword

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WakeWordEngineTest {

    private lateinit var kwsEngine: PhoneticKwsWakeWordEngine
    private lateinit var controller: WakeWordController

    @Before
    fun setUp() {
        kwsEngine = PhoneticKwsWakeWordEngine(targetWord = "JARVIS", sensitivity = 0.8f)
        controller = WakeWordController(engine = kwsEngine)
    }

    @Test
    fun testInitialState() {
        assertEquals(WakeWordState.DISABLED, kwsEngine.currentState)
    }

    @Test
    fun testStartAndStopListening() {
        kwsEngine.startListening {}
        assertEquals(WakeWordState.LISTENING, kwsEngine.currentState)

        kwsEngine.stopListening()
        assertEquals(WakeWordState.DISABLED, kwsEngine.currentState)
    }

    @Test
    fun testDiscriminationJarvisVsOtherWordsAndNoise() {
        var detectedCount = 0
        kwsEngine.startListening {
            detectedCount++
        }

        // 1. Silence -> NOT DETECTED
        val silenceFrame = ShortArray(512) { 10 }
        kwsEngine.processAudioFrame(silenceFrame)
        kwsEngine.processAudioFrame(silenceFrame)
        kwsEngine.processAudioFrame(silenceFrame)
        assertEquals(0, detectedCount)

        // 2. Loud noise / clapping -> NOT DETECTED
        val loudNoiseFrame = ShortArray(512) { 12000.toShort() }
        kwsEngine.processAudioFrame(loudNoiseFrame)
        kwsEngine.processAudioFrame(loudNoiseFrame)
        kwsEngine.processAudioFrame(loudNoiseFrame)
        assertEquals(0, detectedCount)

        // 3. Spoken "ALEXA" simulation (vowel energy, missing JH fricative / S sibilant structure) -> NOT DETECTED
        kwsEngine.stopListening()
        kwsEngine.startListening { detectedCount++ }
        val alexaFrame1 = ShortArray(512) { 100 }
        val alexaFrame2 = ShortArray(512) { 100 }
        val alexaFrame3 = ShortArray(512) { 100 }
        kwsEngine.processAudioFrame(alexaFrame1)
        kwsEngine.processAudioFrame(alexaFrame2)
        kwsEngine.processAudioFrame(alexaFrame3)
        assertEquals(0, detectedCount)

        // 4. Spoken "JARVIS" simulation (JH fricative -> AA vowel core -> S sibilant tail) -> DETECTED
        kwsEngine.stopListening()
        kwsEngine.startListening { detectedCount++ }

        val jhFrame = ShortArray(512) { i -> if (i % 2 == 0) 4000.toShort() else (-4000).toShort() }
        val aaFrame = ShortArray(512) { i -> (6000 * Math.sin(i * 0.1)).toInt().toShort() }
        val sFrame = ShortArray(512) { i -> if (i % 2 == 0) 7000.toShort() else (-7000).toShort() }

        kwsEngine.processAudioFrame(jhFrame)
        kwsEngine.processAudioFrame(aaFrame)
        val isMatch = kwsEngine.processAudioFrame(sFrame)

        assertTrue(isMatch)
        assertEquals(1, detectedCount)
        assertEquals(WakeWordState.DETECTED, kwsEngine.currentState)
    }

    @Test
    fun testControllerStateTransitionsAndPause() = runBlocking {
        var wakeWordDetected = false
        controller.startListening {
            wakeWordDetected = true
        }

        assertEquals(WakeWordState.LISTENING, controller.state.value)

        val jhFrame = ShortArray(512) { i -> if (i % 2 == 0) 4000.toShort() else (-4000).toShort() }
        val aaFrame = ShortArray(512) { i -> (6000 * Math.sin(i * 0.1)).toInt().toShort() }
        val sFrame = ShortArray(512) { i -> if (i % 2 == 0) 7000.toShort() else (-7000).toShort() }

        controller.processAudioFrame(jhFrame)
        controller.processAudioFrame(aaFrame)
        controller.processAudioFrame(sFrame)

        assertTrue(wakeWordDetected)
        assertEquals(WakeWordState.COMMAND_LISTENING, controller.state.value)

        controller.onCommandFinished()
        assertEquals(WakeWordState.LISTENING, controller.state.value)
    }
}
