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

class PrimaryVoiceEngine(
    context: Context
) : JarvisVoiceEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var activeOnStart: (() -> Unit)? = null
    private var activeOnDone: (() -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_PRIMARY_INIT_EXCEPTION ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeCr = Locale.Builder().setLanguage("es").setRegion("CR").build()
            val localeEs = Locale.Builder().setLanguage("es").setRegion("ES").build()

            var langResult = tts?.setLanguage(localeCr)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                langResult = tts?.setLanguage(localeEs)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.Builder().setLanguage("es").build())
                }
            }

            tts?.setPitch(0.85f) // Deep technological pitch
            tts?.setSpeechRate(0.98f) // Natural speech rate

            try {
                val voices = tts?.voices
                if (voices != null) {
                    val spanishVoices = voices.filter { it.locale.language == "es" }
                    Log.i("JARVIS", "[JARVIS][VOICE] Installed Spanish voices count: ${spanishVoices.size}")
                    spanishVoices.forEach { v ->
                        Log.i("JARVIS", "[JARVIS][VOICE]   - Voice: ${v.name} (locale: ${v.locale}, features: ${v.features})")
                    }

                    // Strict objective selection: look for explicit male tag in name or features without female indicators
                    val explicitMaleVoice = spanishVoices.firstOrNull { v ->
                        val lowerName = v.name.lowercase()
                        val hasMaleTag = lowerName.contains("male") || lowerName.contains("hombre") || v.features.contains("male")
                        val hasFemaleTag = lowerName.contains("female") || lowerName.contains("mujer") || v.features.contains("female")
                        hasMaleTag && !hasFemaleTag
                    }

                    if (explicitMaleVoice != null) {
                        tts?.voice = explicitMaleVoice
                        Log.i("JARVIS", "[JARVIS][VOICE] Explicitly selected male Spanish voice: ${explicitMaleVoice.name}")
                    } else if (spanishVoices.isNotEmpty()) {
                        val fallbackVoice = spanishVoices.first()
                        tts?.voice = fallbackVoice
                        Log.i("JARVIS", "[JARVIS][VOICE] No explicit male tag in Spanish voices. Selected available Spanish voice '${fallbackVoice.name}'. Install Google Speech Services male voice in Android Settings if needed.")
                    }
                }
            } catch (e: Exception) {
                Log.e("JARVIS", "[JARVIS][VOICE] Error inspecting/setting Spanish voice: ${e.message}")
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i("JARVIS", "[JARVIS][VOICE] primary_tts_started utteranceId=$utteranceId")
                    activeOnStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Log.i("JARVIS", "[JARVIS][VOICE] primary_tts_completed utteranceId=$utteranceId")
                    activeOnDone?.invoke()
                }

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_PRIMARY_UTTERANCE_FAILED id=$utteranceId")
                    activeOnError?.invoke("TTS_PRIMARY_FAILED")
                }
            })

            isInitialized = true
            Log.i("JARVIS", "[JARVIS][VOICE] primary_tts_initialized=true")
        } else {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_PRIMARY_INIT_FAILED status=$status")
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

        if (!isInitialized || tts == null) {
            Log.w("JARVIS", "[JARVIS][VOICE] Primary TTS not initialized, reporting TTS_PRIMARY_FAILED for fallback")
            onError("TTS_PRIMARY_FAILED")
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

            val utteranceId = "jarvis_primary_${System.currentTimeMillis()}"
            val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (res == TextToSpeech.ERROR) {
                Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_PRIMARY_FAILED")
                onError("TTS_PRIMARY_FAILED")
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_PRIMARY_EXCEPTION ${e.message}", e)
            onError("TTS_PRIMARY_FAILED")
        }
    }

    override fun stop() {
        try {
            tts?.stop()
            activeOnStart = null
            activeOnDone = null
            activeOnError = null
            Log.i("JARVIS", "[JARVIS][VOICE] Primary VoiceEngine stopped and queue cleared")
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] Error stopping Primary VoiceEngine: ${e.message}")
        }
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
