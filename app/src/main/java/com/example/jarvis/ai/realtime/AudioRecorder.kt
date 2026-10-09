package com.example.jarvis.ai.realtime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioRecorder(
    private val sampleRate: Int = 24000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startRecording(
        onAudioChunk: (ByteArray, Float) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        if (isRecording) return
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            onError?.invoke("Capacidad de AudioRecord no soportada en este dispositivo")
            return
        }
        val bufferSize = maxOf(minBufferSize, 4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("No se pudo inicializar AudioRecord. Verifica el permiso de micrófono.")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(bufferSize)
                while (isRecording && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val chunk = buffer.copyOfRange(0, read)
                        val level = calculateAudioLevel(buffer, read)
                        onAudioChunk(chunk, level)
                    }
                }
            }
        } catch (e: Exception) {
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            onError?.invoke("Error al iniciar captura de micrófono: ${e.message}")
        }
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun calculateAudioLevel(buffer: ByteArray, bytesRead: Int): Float {
        if (bytesRead <= 0) return 0f
        var sum = 0.0
        var i = 0
        while (i < bytesRead - 1) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
            i += 2
        }
        val rms = sqrt(sum / (bytesRead / 2))
        return (rms / 32767.0 * 3.5).coerceIn(0.0, 1.0).toFloat()
    }
}

