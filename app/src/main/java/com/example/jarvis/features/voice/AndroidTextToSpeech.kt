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

class AndroidTextToSpeech(
    context: Context,
    private val onStart: () -> Unit = {},
    private val onDone: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("VOICE_PIPELINE", "Error initializing TextToSpeech", e)
            onError("TTS_INIT_ERROR: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeEs = Locale.Builder().setLanguage("es").setRegion("ES").build()
            val result = tts?.setLanguage(localeEs)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.Builder().setLanguage("es").build())
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i("VOICE_PIPELINE", "[VOICE_PIPELINE] tts_started=true utteranceId=$utteranceId")
                    onStart()
                }

                override fun onDone(utteranceId: String?) {
                    Log.i("VOICE_PIPELINE", "[VOICE_PIPELINE] tts_completed=true utteranceId=$utteranceId")
                    onDone()
                }

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    Log.e("VOICE_PIPELINE", "[VOICE_PIPELINE] stage=TTS error=UtteranceError id=$utteranceId")
                    onError("TTS_UTTERANCE_ERROR")
                }
            })

            isInitialized = true
            Log.i("VOICE_PIPELINE", "[VOICE_PIPELINE] tts_initialized=true")
        } else {
            Log.e("VOICE_PIPELINE", "[VOICE_PIPELINE] stage=TTS error=InitializationFailed status=$status")
            onError("TTS_INIT_FAILED")
        }
    }

    fun speak(text: String, utteranceId: String = "jarvis_tts_${System.currentTimeMillis()}") {
        if (text.isBlank()) return

        if (!isInitialized || tts == null) {
            Log.w("VOICE_PIPELINE", "[VOICE_PIPELINE] TTS not ready yet, skipping speak")
            onError("TTS_NOT_READY")
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

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            Log.e("VOICE_PIPELINE", "[VOICE_PIPELINE] stage=TTS exception=${e.message}", e)
            onError("TTS_SPEAK_EXCEPTION: ${e.message}")
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (_: Exception) {}
    }
}
