package com.example.jarvis.voice.wakeword

/**
 * Detector LOCAL de la palabra "JARVIS". Escucha en el teléfono sin enviar audio a ningún
 * servidor; solo al detectarla se abre una sesión de voz (con costo de API).
 * La implementación real (Sherpa-ONNX) se compila solo si su AAR está en app/libs.
 */
interface WakeWordEngine {
    val isAvailable: Boolean
    val unavailableReason: String?

    /** Empieza a escuchar. Devuelve false si no pudo abrir el micrófono o el modelo. */
    fun start(onDetected: () -> Unit, onError: (String) -> Unit): Boolean

    /** Deja de escuchar y libera el micrófono (necesario antes de abrir la sesión de voz). */
    fun stop()
}
