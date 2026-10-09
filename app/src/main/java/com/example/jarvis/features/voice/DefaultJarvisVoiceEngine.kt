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

class DefaultJarvisVoiceEngine(
    context: Context
) : JarvisVoiceEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var pendingSpeakText: String? = null
    private var pendingOnStart: (() -> Unit)? = null
    private var pendingOnDone: (() -> Unit)? = null
    private var pendingOnError: ((String) -> Unit)? = null

    private var activeOnStart: (() -> Unit)? = null
    private var activeOnDone: (() -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_INIT_EXCEPTION ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeEs = Locale.Builder().setLanguage("es").setRegion("ES").build()
            var langResult = tts?.setLanguage(localeEs)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.Builder().setLanguage("es").build())
            }

            tts?.setPitch(0.92f)
            tts?.setSpeechRate(0.98f)

            try {
                val voices = tts?.voices
                if (voices != null) {
                    val maleVoice = voices.firstOrNull { v ->
                        v.locale.language == "es" && (v.name.contains("male", ignoreCase = true) || v.name.contains("es-es-x", ignoreCase = true) || !v.name.contains("female", ignoreCase = true))
                    }
                    if (maleVoice != null) {
                        tts?.voice = maleVoice
                    }
                }
            } catch (_: Exception) {}

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i("JARVIS", "[JARVIS][VOICE] tts_started utteranceId=$utteranceId")
                    activeOnStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Log.i("JARVIS", "[JARVIS][VOICE] tts_completed utteranceId=$utteranceId")
                    activeOnDone?.invoke()
                }

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_UTTERANCE_FAILED id=$utteranceId")
                    activeOnError?.invoke("TTS_UTTERANCE_FAILED")
                }
            })

            isInitialized = true
            Log.i("JARVIS", "[JARVIS][VOICE] tts_initialized=true")

            pendingSpeakText?.let { text ->
                val start = pendingOnStart
                val done = pendingOnDone
                val err = pendingOnError
                pendingSpeakText = null
                pendingOnStart = null
                pendingOnDone = null
                pendingOnError = null
                speak(text, start ?: {}, done ?: {}, err ?: {})
            }
        } else {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_INIT_FAILED status=$status")
            activeOnError?.invoke("TTS_INIT_FAILED")
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

        if (!isInitialized || tts == null) {
            Log.w("JARVIS", "[JARVIS][VOICE] TTS not initialized yet, queueing pending speech")
            pendingSpeakText = text
            pendingOnStart = onStart
            pendingOnDone = onDone
            pendingOnError = onError
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

            val utteranceId = "jarvis_speech_${System.currentTimeMillis()}"
            val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (res == TextToSpeech.ERROR) {
                Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_PRIMARY_FAILED")
                onError("TTS_PRIMARY_FAILED")
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_SPEAK_EXCEPTION ${e.message}", e)
            onError("TTS_SPEAK_EXCEPTION: ${e.message}")
        }
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    override fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (_: Exception) {}
    }
}
