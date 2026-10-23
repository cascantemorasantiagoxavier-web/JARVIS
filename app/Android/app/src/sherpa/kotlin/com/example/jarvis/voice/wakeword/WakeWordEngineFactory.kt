package com.example.jarvis.voice.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread

/**
 * Compilación CON Sherpa-ONNX (Apache-2.0). Escrito contra la API Kotlin de sherpa-onnx
 * (KeywordSpotter / OnlineStream). Requiere en assets/kws: encoder.onnx, decoder.onnx,
 * joiner.onnx, tokens.txt y keywords.txt (generados por scripts/fetch-sherpa-kws.sh).
 * VERIFICAR al compilar con el AAR descargado: este archivo no pudo compilarse en el entorno
 * donde se escribió (sin acceso a GitHub).
 */
object WakeWordEngineFactory {
    fun create(context: Context, sensitivity: Float): WakeWordEngine = SherpaWakeWordEngine(context.applicationContext, sensitivity)
}

private class SherpaWakeWordEngine(private val context: Context, sensitivity: Float) : WakeWordEngine {
    private val assetsPresent = runCatching { context.assets.list("kws")?.toSet().orEmpty() }.getOrDefault(emptySet())
        .containsAll(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt"))

    override val isAvailable: Boolean = assetsPresent
    override val unavailableReason: String? = if (assetsPresent) null else "Faltan los archivos del modelo en assets/kws (ejecuta scripts/fetch-sherpa-kws.sh)."

    // Mayor sensibilidad → umbral más bajo (más detecciones, más falsos positivos).
    private val threshold = (0.45f - sensitivity * 0.35f).coerceIn(0.05f, 0.5f)
    @Volatile private var running = false
    private var worker: Thread? = null
    private var spotter: KeywordSpotter? = null

    @SuppressLint("MissingPermission")
    override fun start(onDetected: () -> Unit, onError: (String) -> Unit): Boolean {
        if (!isAvailable || running) return running
        val kws = spotter ?: try {
            KeywordSpotter(
                assetManager = context.assets,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(encoder = "kws/encoder.onnx", decoder = "kws/decoder.onnx", joiner = "kws/joiner.onnx"),
                        tokens = "kws/tokens.txt",
                        modelType = "zipformer2",
                        numThreads = 1,
                    ),
                    keywordsFile = "kws/keywords.txt",
                    keywordsThreshold = threshold,
                ),
            ).also { spotter = it }
        } catch (e: Throwable) {
            onError("No se pudo cargar el modelo de palabra clave: ${e.message}")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 5 * 2))
        } catch (e: Exception) {
            onError("No se pudo abrir el micrófono: ${e.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("El micrófono está ocupado por otra aplicación.")
            return false
        }
        running = true
        worker = thread(name = "jarvis-kws", isDaemon = true) {
            val stream: OnlineStream = kws.createStream()
            val buf = ShortArray(SAMPLE_RATE / 10)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buf[it] / 32768f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        val keyword = kws.getResult(stream).keyword
                        if (keyword.isNotBlank()) {
                            kws.reset(stream)
                            running = false
                            onDetected()
                            break
                        }
                    }
                }
            } catch (e: Throwable) {
                if (running) onError("La escucha de palabra clave se detuvo: ${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
                stream.release()
                running = false
            }
        }
        return true
    }

    override fun stop() {
        running = false
        worker?.join(600)
        worker = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
