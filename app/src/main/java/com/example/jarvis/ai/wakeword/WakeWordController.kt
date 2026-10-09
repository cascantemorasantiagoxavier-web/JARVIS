package com.example.jarvis.ai.wakeword

import com.example.jarvis.core.events.RobustEventBus
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.domain.events.AppEvent
import com.example.jarvis.domain.events.EventSource
import com.example.jarvis.domain.events.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WakeWordController(
    private val engine: WakeWordEngine = PhoneticKwsWakeWordEngine(),
    private val eventBus: RobustEventBus? = null,
    private val logger: AppLogger = ConsoleLogger(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _state = MutableStateFlow(WakeWordState.DISABLED)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    fun startListening(onWakeWordDetected: () -> Unit) {
        engine.startListening {
            _state.value = WakeWordState.DETECTED
            logger.i("WakeWordController", "JARVIS Wake Word Triggered!")

            scope.launch {
                eventBus?.publish(
                    AppEvent(
                        type = EventType.CUSTOM,
                        source = EventSource.SYSTEM,
                        data = mapOf("action" to "WAKE_WORD_DETECTED", "keyword" to engine.targetWord)
                    )
                )
            }

            engine.pause()
            _state.value = WakeWordState.COMMAND_LISTENING
            onWakeWordDetected()
        }
        _state.value = WakeWordState.LISTENING
    }

    fun onCommandFinished() {
        if (_state.value == WakeWordState.COMMAND_LISTENING || _state.value == WakeWordState.PROCESSING || _state.value == WakeWordState.SPEAKING) {
            logger.i("WakeWordController", "Command finished. Resuming Wake Word listening.")
            engine.resume()
            _state.value = WakeWordState.LISTENING
        }
    }

    fun stopListening() {
        engine.stopListening()
        _state.value = WakeWordState.DISABLED
    }

    fun processAudioFrame(frame: ShortArray): Boolean {
        return engine.processAudioFrame(frame)
    }

    fun release() {
        engine.release()
        _state.value = WakeWordState.DISABLED
    }
}
