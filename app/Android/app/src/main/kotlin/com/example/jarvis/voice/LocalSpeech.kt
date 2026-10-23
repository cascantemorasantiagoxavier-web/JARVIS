package com.example.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

sealed interface ListenResult {
    data class Heard(val text: String) : ListenResult
    data object Silence : ListenResult
    data class Failed(val reason: String) : ListenResult
}

/**
 * Voz local de respaldo (sin costo de API): SpeechRecognizer + TextToSpeech de Android.
 * El micrófono no escucha mientras se habla (se alternan), evitando que JARVIS se oiga a sí mismo.
 */
class LocalSpeech(private val context: Context, private val levels: AudioLevels) {
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private var recognizer: SpeechRecognizer? = null

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
    }

    suspend fun spanishVoices(): List<Voice> {
        init()
        if (withTimeoutOrNull(5_000) { ttsReady.await() } != true) return emptyList()
        return tts?.voices.orEmpty().filter { it.locale.language == "es" && !it.isNetworkConnectionRequired }.sortedBy { it.name }
    }

    suspend fun listenOnce(timeoutMs: Long = 12_000): ListenResult = withContext(Dispatchers.Main) {
        if (!recognitionAvailable) return@withContext ListenResult.Failed("Este teléfono no tiene reconocimiento de voz disponible.")
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ListenResult> { cont ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { levels.mic(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) {
                        if (!cont.isActive) return
                        cont.resume(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenResult.Silence
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenResult.Failed("Falta el permiso de micrófono.")
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ListenResult.Failed("El reconocimiento de voz del teléfono necesita conexión.")
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenResult.Failed("El reconocimiento de voz está ocupado.")
                                else -> ListenResult.Failed("Error de reconocimiento de voz ($error).")
                            },
                        )
                    }
                    override fun onResults(results: Bundle?) {
                        if (!cont.isActive) return
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        cont.resume(if (text.isNullOrEmpty()) ListenResult.Silence else ListenResult.Heard(text))
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CR")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-CR")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                r.startListening(intent)
                cont.invokeOnCancellation { runCatching { r.cancel() } }
            }
        } ?: ListenResult.Silence
        levels.reset()
        result
    }

    suspend fun speak(text: String, voiceName: String?, rate: Float, pitch: Float): Boolean {
        init()
        if (withTimeoutOrNull(5_000) { ttsReady.await() } != true) return false
        val engine = tts ?: return false
        withContext(Dispatchers.Main) {
            val voice = voiceName?.let { name -> engine.voices?.firstOrNull { it.name == name } }
            // setVoice/setLanguage devuelven int: no se pueden usar como propiedades Kotlin.
            if (voice != null) engine.setVoice(voice) else engine.setLanguage(Locale.forLanguageTag("es-ES"))
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { levels.output(0.6f) }
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(false) }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) { levels.output(0.35f + (start % 7) / 14f) }
        })
        val clean = text.replace(Regex("[*#_`>\\[\\]]"), " ").take(3500)
        if (engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return false
        val ok = done.await()
        levels.reset()
        return ok
    }

    fun stopSpeaking() {
        tts?.stop()
        levels.reset()
    }

    fun cancelListening() {
        recognizer?.cancel()
    }

    fun release() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }
}
