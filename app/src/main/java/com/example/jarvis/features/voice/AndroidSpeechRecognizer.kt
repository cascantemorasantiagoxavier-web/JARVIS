package com.example.jarvis.features.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class AndroidSpeechRecognizer(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
    private val onAudioLevel: (Float) -> Unit = {}
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startListeningCount = 0
    private var isListening = false

    fun startListening() {
        mainHandler.post {
            if (isListening) {
                Log.w("JARVIS", "[JARVIS][STT] DUPLICATE_START_BLOCKED=true")
                return@post
            }
            isListening = true

            try {
                startListeningCount++
                Log.i("JARVIS", "[JARVIS][STT] START_LISTENING_COUNT=$startListeningCount")
                Log.i("JARVIS", "[JARVIS][STT] START_LISTENING_REQUESTED")

                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                } catch (_: Exception) {}
                speechRecognizer = null

                val isAvail = SpeechRecognizer.isRecognitionAvailable(context)
                Log.i("JARVIS", "[JARVIS][STT] RECOGNITION_AVAILABLE=$isAvail")

                val isOnDeviceAvail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                } else false
                Log.i("JARVIS", "[JARVIS][STT] ON_DEVICE_AVAILABLE=$isOnDeviceAvail")

                if (!isAvail && !isOnDeviceAvail) {
                    isListening = false
                    Log.e("JARVIS", "[JARVIS][STT] ERROR_CODE=STT_NOT_AVAILABLE")
                    onError("STT_NOT_AVAILABLE: El reconocimiento de voz no está disponible.")
                    return@post
                }

                val sysLocale = Locale.getDefault().toString()
                val reqLocale = Locale.getDefault().toLanguageTag().ifBlank { "es-US" }
                Log.i("JARVIS", "[JARVIS][STT] SYSTEM_LOCALE=$sysLocale")
                Log.i("JARVIS", "[JARVIS][STT] REQUESTED_LOCALE=$reqLocale")

                speechRecognizer = if (isOnDeviceAvail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Log.i("JARVIS", "[JARVIS][STT] STT_MODE=ON_DEVICE")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
                } else {
                    Log.i("JARVIS", "[JARVIS][STT] STT_MODE=NETWORK")
                    SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                }
                Log.i("JARVIS", "[JARVIS][STT] INSTANCE_CREATED")

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, reqLocale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, reqLocale)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i("JARVIS", "[JARVIS][STT] ON_READY_FOR_SPEECH")
                        onReady()
                    }

                    override fun onBeginningOfSpeech() {
                        Log.i("JARVIS", "[JARVIS][STT] ON_BEGINNING_OF_SPEECH")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB - 1f) / 9f).coerceIn(0f, 1f)
                        Log.i("JARVIS", "[JARVIS][STT] ON_RMS_CHANGED=$rmsdB")
                        onAudioLevel(normalized)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.i("JARVIS", "[JARVIS][STT] ON_END_OF_SPEECH")
                        onAudioLevel(0f)
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        onAudioLevel(0f)
                        val errName = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO (3)"
                            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT (5)"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS (9)"
                            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK (2)"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT (1)"
                            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH (7)"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY (8)"
                            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER (4)"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT (6)"
                            10 -> "ERROR_CANNOT_CHECK_SECURITY (10)"
                            11 -> "ERROR_TOO_MANY_REQUESTS (11)"
                            12 -> "ERROR_SERVER_DISCONNECTED (12)"
                            13 -> "ERROR_LANGUAGE_NOT_SUPPORTED (13)"
                            14 -> "ERROR_LANGUAGE_UNAVAILABLE (14)"
                            else -> "ERROR_$error ($error)"
                        }
                        Log.e("JARVIS", "[JARVIS][STT] ON_ERROR=$errName (code=$error)")
                        onError(errName)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        onAudioLevel(0f)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.i("JARVIS", "[JARVIS][STT] ON_RESULTS text=\"$text\"")
                        if (text.isNotBlank()) {
                            onResult(text)
                        } else {
                            onError("ERROR_NO_MATCH")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            Log.i("JARVIS", "[JARVIS][STT] ON_PARTIAL_RESULTS text=\"$text\"")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                onAudioLevel(0f)
                Log.e("JARVIS", "[JARVIS][STT] EXCEPTION=${e.javaClass.simpleName} EXCEPTION_MESSAGE=${e.message}", e)
                onError("MICROPHONE_INIT_ERROR: ${e.message}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            isListening = false
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            onAudioLevel(0f)
        }
    }

    fun destroy() {
        mainHandler.post {
            isListening = false
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                Log.i("JARVIS", "[JARVIS][STT] INSTANCE_DESTROYED")
            } catch (_: Exception) {}
            onAudioLevel(0f)
        }
    }
}
