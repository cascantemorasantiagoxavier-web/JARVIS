package com.example.jarvis.voice.wakeword

import android.content.Context

/** Compilación sin Sherpa-ONNX: la palabra de activación no está disponible y se explica por qué. */
object WakeWordEngineFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") sensitivity: Float): WakeWordEngine = object : WakeWordEngine {
        override val isAvailable = false
        override val unavailableReason =
            "Este APK se compiló sin el motor local de palabra clave (Sherpa-ONNX). Ejecuta scripts/fetch-sherpa-kws.sh y vuelve a compilar. " +
                "Mientras tanto, pulsa el micrófono: la conversación sigue abierta sin volver a pulsar hasta que haya silencio."
        override fun start(onDetected: () -> Unit, onError: (String) -> Unit) = false
        override fun stop() {}
    }
}
