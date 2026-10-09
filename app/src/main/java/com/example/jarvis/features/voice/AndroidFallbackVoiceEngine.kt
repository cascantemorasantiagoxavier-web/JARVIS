package com.example.jarvis.features.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AndroidFallbackVoiceEngine(
    context: Context
) : JarvisVoiceEngine, TextToSpeech.OnInitListener {

    private var fallbackTts: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var activeOnStart: (() -> Unit)? = null
    private var activeOnDone: (() -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    init {
        try {
            fallbackTts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_FALLBACK_INIT_EXCEPTION ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            fallbackTts?.setLanguage(Locale.Builder().setLanguage("es").build())

            fallbackTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i("JARVIS", "[JARVIS][VOICE] fallback_tts_started utteranceId=$utteranceId")
                    activeOnStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Log.i("JARVIS", "[JARVIS][VOICE] fallback_tts_completed utteranceId=$utteranceId")
                    activeOnDone?.invoke()
                }

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_FALLBACK_FAILED id=$utteranceId")
                    activeOnError?.invoke("TTS_FALLBACK_FAILED")
                }
            })

            isInitialized = true
            Log.i("JARVIS", "[JARVIS][VOICE] fallback_tts_initialized=true")
        } else {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_FALLBACK_INIT_FAILED status=$status")
            isInitialized = false
        }
    }

    override fun speak(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (text.isBlank()) return

        activeOnStart = onStart
        activeOnDone = onDone
        activeOnError = onError

        if (!isInitialized || fallbackTts == null) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_FALLBACK_FAILED (not ready)")
            onError("TTS_FALLBACK_FAILED")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }

            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)

            val utteranceId = "jarvis_fallback_${System.currentTimeMillis()}"
            val res = fallbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (res == TextToSpeech.ERROR) {
                Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_FALLBACK_FAILED")
                onError("TTS_FALLBACK_FAILED")
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_FALLBACK_EXCEPTION ${e.message}", e)
            onError("TTS_FALLBACK_FAILED")
        }
    }

    override fun stop() {
        try {
            fallbackTts?.stop()
        } catch (_: Exception) {}
    }

    override fun shutdown() {
        try {
            fallbackTts?.stop()
            fallbackTts?.shutdown()
            fallbackTts = null
            isInitialized = false
        } catch (_: Exception) {}
    }
}
