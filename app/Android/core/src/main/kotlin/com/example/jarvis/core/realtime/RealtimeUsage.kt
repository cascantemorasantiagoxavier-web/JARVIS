package com.example.jarvis.core.realtime

/**
 * Precios en USD por millón de tokens. Son ESTIMACIONES tomadas de fuentes públicas
 * (ver JARVIS-KB/07-RESEARCH/openai-realtime.md); verifica la página oficial de precios
 * antes de confiar en ellos. El precio del modelo completo (FULL) no está confirmado.
 */
data class PriceTable(
    val audioInPerM: Double,
    val cachedAudioInPerM: Double,
    val audioOutPerM: Double,
    val textInPerM: Double,
    val textOutPerM: Double,
) {
    companion object {
        val MINI = PriceTable(10.0, 0.30, 20.0, 0.60, 2.40)
        val FULL = PriceTable(32.0, 0.40, 64.0, 4.0, 24.0)
        fun forModel(model: String?): PriceTable = if (model?.contains("mini") == true) MINI else FULL
    }
}

/** Uso acumulado de Realtime (sesión actual y total del día). */
data class RealtimeUsage(
    val audioIn: Long = 0,
    val cachedAudioIn: Long = 0,
    val textIn: Long = 0,
    val audioOut: Long = 0,
    val textOut: Long = 0,
    val responses: Int = 0,
    val sessionSeconds: Long = 0,
) {
    operator fun plus(d: UsageDelta) = copy(
        audioIn = audioIn + d.audioIn,
        cachedAudioIn = cachedAudioIn + d.cachedAudioIn,
        textIn = textIn + d.textIn,
        audioOut = audioOut + d.audioOut,
        textOut = textOut + d.textOut,
        responses = responses + 1,
    )

    fun estimatedCostUsd(p: PriceTable): Double {
        val uncachedAudio = (audioIn - cachedAudioIn).coerceAtLeast(0)
        return (uncachedAudio * p.audioInPerM + cachedAudioIn * p.cachedAudioInPerM + audioOut * p.audioOutPerM +
            textIn * p.textInPerM + textOut * p.textOutPerM) / 1_000_000.0
    }
}
