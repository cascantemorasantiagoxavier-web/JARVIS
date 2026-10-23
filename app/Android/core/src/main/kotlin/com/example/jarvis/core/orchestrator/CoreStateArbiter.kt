package com.example.jarvis.core.orchestrator

import com.example.jarvis.core.model.CoreState
import com.example.jarvis.core.realtime.VoiceState

/**
 * Combina el estado del cerebro (chat/botones) y el de la voz en un único estado del núcleo.
 * Prioridad: ERROR > EXECUTING > SPEAKING > LISTENING > THINKING > IDLE.
 * La espera de palabra clave no es "escuchando": el núcleo queda en IDLE con el micrófono armado.
 */
object CoreStateArbiter {
    fun voiceToCore(v: VoiceState): CoreState = when (v) {
        VoiceState.OFF, VoiceState.WAKE_WORD -> CoreState.IDLE
        VoiceState.CONNECTING, VoiceState.THINKING -> CoreState.THINKING
        VoiceState.LISTENING, VoiceState.USER_SPEAKING -> CoreState.LISTENING
        VoiceState.EXECUTING -> CoreState.EXECUTING
        VoiceState.SPEAKING -> CoreState.SPEAKING
        VoiceState.ERROR -> CoreState.ERROR
    }

    private val priority = listOf(CoreState.ERROR, CoreState.EXECUTING, CoreState.SPEAKING, CoreState.LISTENING, CoreState.THINKING, CoreState.IDLE)

    fun combine(brain: CoreState, voice: VoiceState): CoreState {
        val v = voiceToCore(voice)
        return priority.first { it == brain || it == v }
    }
}
