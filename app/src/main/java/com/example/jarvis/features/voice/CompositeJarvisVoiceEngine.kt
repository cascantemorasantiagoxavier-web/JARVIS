package com.example.jarvis.features.voice

import android.content.Context
import android.util.Log

class CompositeJarvisVoiceEngine(
    context: Context
) : JarvisVoiceEngine {

    private val primaryEngine = PrimaryVoiceEngine(context)
    private val fallbackEngine = AndroidFallbackVoiceEngine(context)

    override fun speak(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        primaryEngine.speak(
            text = text,
            onStart = onStart,
            onDone = onDone,
            onError = { primaryErr ->
                Log.w("JARVIS", "[JARVIS][VOICE] error=TTS_PRIMARY_FAILED ($primaryErr), switching to AndroidFallbackVoiceEngine...")
                fallbackEngine.speak(
                    text = text,
                    onStart = onStart,
                    onDone = onDone,
                    onError = { fallbackErr ->
                        Log.e("JARVIS", "[JARVIS][VOICE] error=TTS_FALLBACK_FAILED ($fallbackErr)")
                        onError("AUDIO_OUTPUT_FAILED")
                    }
                )
            }
        )
    }

    override fun stop() {
        primaryEngine.stop()
        fallbackEngine.stop()
    }

    override fun shutdown() {
        primaryEngine.shutdown()
        fallbackEngine.shutdown()
    }
}
