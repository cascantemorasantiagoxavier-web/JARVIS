package com.example.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.jarvis.ai.wakeword.WakeWordService
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.di.JarvisRequest
import com.example.jarvis.core.di.RequestSource
import com.example.jarvis.core.permissions.JarvisPermissionManager
import com.example.jarvis.core.util.SpeechTextFormatter
import com.example.jarvis.features.navigation.JarvisMainScreen
import com.example.jarvis.features.voice.AndroidSpeechRecognizer
import com.example.jarvis.features.voice.CompositeJarvisVoiceEngine
import com.example.jarvis.features.voice.VoiceState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var ttsEngine: CompositeJarvisVoiceEngine
    private lateinit var sttRecognizer: AndroidSpeechRecognizer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState = VoiceState.IDLE
    private var isSttActive = false
    private var conversationActive = false

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_WAKE_WORD_DETECTED) {
                if (currentState != VoiceState.IDLE) {
                    Log.w("JARVIS", "[JARVIS][WAKE] WAKE_WORD_DETECTED ignored because state is $currentState")
                    return
                }

                conversationActive = true
                currentState = currentState.transitionTo(VoiceState.WAKE_DETECTED)
                Log.i("JARVIS", "[JARVIS][WAKE] WAKE_WORD_DETECTED: 'JARVIS' acoustic pattern matched!")
                Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=TTS")

                currentState = currentState.transitionTo(VoiceState.TTS_CONFIRMING)
                Log.i("JARVIS", "[JARVIS][TTS] WAKE_CONFIRMATION_STARTED")

                ttsEngine.speak(
                    text = "Sí, jefe.",
                    onStart = { Log.i("JARVIS", "[JARVIS][TTS] CONFIRMATION_SPEAK_STARTED") },
                    onDone = {
                        Log.i("JARVIS", "[JARVIS][TTS] WAKE_CONFIRMATION_COMPLETED")
                        currentState = currentState.transitionTo(VoiceState.TTS_CONFIRMED)
                        Log.i("JARVIS", "[JARVIS][STT] START_AFTER_CONFIRMATION=true")

                        if (!isSttActive) {
                            isSttActive = true
                            currentState = currentState.transitionTo(VoiceState.STT_LISTENING)
                            Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=STT")
                            mainHandler.postDelayed({
                                sttRecognizer.startListening()
                            }, 300L)
                        } else {
                            Log.w("JARVIS", "[JARVIS][STT] DUPLICATE_START_BLOCKED=true")
                        }
                    },
                    onError = { err ->
                        Log.e("JARVIS", "[JARVIS][TTS] CONFIRMATION_SPEAK_ERROR=$err")
                        currentState = currentState.transitionTo(VoiceState.TTS_CONFIRMED)
                        if (!isSttActive) {
                            isSttActive = true
                            currentState = currentState.transitionTo(VoiceState.STT_LISTENING)
                            Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=STT")
                            mainHandler.postDelayed({
                                sttRecognizer.startListening()
                            }, 300L)
                        }
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        AppContainer.initializeAppManager(this)

        ttsEngine = CompositeJarvisVoiceEngine(applicationContext)

        sttRecognizer = AndroidSpeechRecognizer(
            context = applicationContext,
            onResult = { text ->
                isSttActive = false
                currentState = currentState.transitionTo(VoiceState.STT_RESULT)
                Log.i("JARVIS", "[JARVIS][VOICE] SPEECH_RESULT=$text")
                handleSpokenCommand(text)
            },
            onError = { err ->
                isSttActive = false
                Log.e("JARVIS", "[JARVIS][VOICE] SPEECH_ERROR=$err")
                Log.i("JARVIS", "[JARVIS][STT] RECOVERY_STARTED=true")
                conversationActive = false
                currentState = currentState.transitionTo(VoiceState.IDLE)
                Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
                Log.i("JARVIS", "[JARVIS][STT] RECOVERY_COMPLETED=true")
                sendBroadcast(Intent(WakeWordService.ACTION_RESUME_WAKE_WORD))
            },
            onReady = {
                Log.i("JARVIS", "[JARVIS][VOICE] SPEECH_STARTED")
            }
        )

        JarvisPermissionManager.auditAndLogPermissions(this)
        JarvisPermissionManager.requestMissingPermissions(this)

        try {
            WakeWordService.startService(this)
            Log.i("JARVIS", "[JARVIS][VOICE] WAKE_SERVICE_START_SUCCESS=true")
            Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] WAKE_SERVICE_START_FAILED=${e.message}", e)
        }

        registerWakeWordReceiver()

        setContent {
            JarvisApp()
        }
    }

    private fun registerWakeWordReceiver() {
        try {
            val filter = IntentFilter(WakeWordService.ACTION_WAKE_WORD_DETECTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeWordReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(wakeWordReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "Failed to register wakeWordReceiver in MainActivity: ${e.message}", e)
        }
    }

    private fun handleSpokenCommand(text: String) {
        lifecycleScope.launch {
            val lowerText = text.lowercase().trim()

            val isStopCommand = lowerText == "jarvis para" || lowerText == "para" || lowerText == "silencio" || lowerText == "detener"
            if (isStopCommand) {
                Log.i("JARVIS", "[JARVIS][VOICE] VOICE_INTERRUPTION_TRIGGERED=true")
                ttsEngine.stop()
                currentState = VoiceState.IDLE
                Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
                sendBroadcast(Intent(WakeWordService.ACTION_RESUME_WAKE_WORD))
                return@launch
            }

            val isExitPhrase = lowerText.contains("adiós") || lowerText.contains("adios") ||
                               lowerText.contains("terminar conversación") || lowerText.contains("terminar conversacion") ||
                               lowerText.contains("hasta luego") || lowerText.contains("gracias jarvis")

            if (isExitPhrase) {
                conversationActive = false
                Log.i("JARVIS", "[JARVIS][VOICE] EXIT_PHRASE_DETECTED conversationActive=false")
                currentState = currentState.transitionTo(VoiceState.TTS_RESPONSE)
                Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=TTS")

                ttsEngine.speak(
                    text = "Hasta luego, jefe.",
                    onStart = { Log.i("JARVIS", "[JARVIS][TTS] SPEAK_STARTED") },
                    onDone = {
                        Log.i("JARVIS", "[JARVIS][TTS] SPEAK_COMPLETED")
                        currentState = currentState.transitionTo(VoiceState.IDLE)
                        Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
                        sendBroadcast(Intent(WakeWordService.ACTION_RESUME_WAKE_WORD))
                    },
                    onError = { err ->
                        Log.e("JARVIS", "[JARVIS][TTS] SPEAK_ERROR=$err")
                        currentState = currentState.transitionTo(VoiceState.IDLE)
                        Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
                        sendBroadcast(Intent(WakeWordService.ACTION_RESUME_WAKE_WORD))
                    }
                )
                return@launch
            }

            conversationActive = true
            currentState = currentState.transitionTo(VoiceState.BRAIN_PROCESSING)
            Log.i("JARVIS", "[JARVIS][BRAIN] REQUEST_RECEIVED source=VOICE query=\"$text\"")
            val req = JarvisRequest(text = text, source = RequestSource.VOICE)
            val res = JarvisBrain.processRequest(req)
            Log.i("JARVIS", "[JARVIS][BRAIN] RESULT_RECEIVED text=\"${res.text}\"")

            currentState = currentState.transitionTo(VoiceState.TTS_RESPONSE)
            Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=TTS")

            val spokenText = SpeechTextFormatter.formatForSpeech(res.text)

            ttsEngine.speak(
                text = spokenText,
                onStart = { Log.i("JARVIS", "[JARVIS][TTS] SPEAK_STARTED") },
                onDone = {
                    Log.i("JARVIS", "[JARVIS][VOICE_STATE] TTS_RESPONDING -> TTS_COMPLETED")
                    Log.i("JARVIS", "[JARVIS][TTS] SPEAK_COMPLETED")
                    if (conversationActive) {
                        Log.i("JARVIS", "[JARVIS][VOICE_STATE] TTS_COMPLETED -> LISTENING")
                        Log.i("JARVIS", "[JARVIS][STT] AUTO_LISTEN_AFTER_RESPONSE=true")
                        Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=STT")
                        if (!isSttActive) {
                            isSttActive = true
                            currentState = VoiceState.STT_LISTENING
                            mainHandler.postDelayed({
                                sttRecognizer.startListening()
                            }, 350L)
                        }
                    } else {
                        currentState = currentState.transitionTo(VoiceState.IDLE)
                        Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
                        sendBroadcast(Intent(WakeWordService.ACTION_RESUME_WAKE_WORD))
                    }
                },
                onError = { err ->
                    Log.e("JARVIS", "[JARVIS][TTS] SPEAK_ERROR=$err")
                    currentState = currentState.transitionTo(VoiceState.IDLE)
                    Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
                    sendBroadcast(Intent(WakeWordService.ACTION_RESUME_WAKE_WORD))
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(wakeWordReceiver) } catch (_: Exception) {}
        sttRecognizer.destroy()
        ttsEngine.shutdown()
    }
}

@Composable
fun JarvisApp() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            JarvisMainScreen()
        }
    }
}
