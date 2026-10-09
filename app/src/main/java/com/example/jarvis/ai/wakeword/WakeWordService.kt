package com.example.jarvis.ai.wakeword

import android.R
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WakeWordService : Service() {

    private val logger: AppLogger = ConsoleLogger()
    private val controller: WakeWordController = WakeWordController()
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var isPaused = false

    companion object {
        const val ACTION_WAKE_WORD_DETECTED = "com.example.jarvis.WAKE_WORD_DETECTED"
        const val ACTION_PAUSE_WAKE_WORD = "com.example.jarvis.PAUSE_WAKE_WORD"
        const val ACTION_RESUME_WAKE_WORD = "com.example.jarvis.RESUME_WAKE_WORD"

        private const val CHANNEL_ID = "jarvis_wake_word_channel"
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (_: Exception) {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.stopService(intent)
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_WAKE_WORD -> pauseAudioCapture()
                ACTION_RESUME_WAKE_WORD -> resumeAudioCapture()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("JARVIS", "[JARVIS][VOICE] WAKE_SERVICE_CREATED")
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        Log.i("JARVIS", "[JARVIS][VOICE] WAKE_SERVICE_STARTED")
        Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")

        registerControlReceiver()

        controller.startListening {
            logger.i("WakeWordService", "WAKE_WORD_DETECTED: Wake Word 'JARVIS' detected in background service!")
            Log.i("JARVIS", "[JARVIS][WAKE] WAKE_WORD_DETECTED")
            pauseAudioCapture()

            val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        startAudioCaptureLoop()
    }

    private fun registerControlReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_PAUSE_WAKE_WORD)
                addAction(ACTION_RESUME_WAKE_WORD)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(controlReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "Failed to register controlReceiver: ${e.message}", e)
        }
    }

    private fun pauseAudioCapture() {
        if (!isPaused) {
            isPaused = true
            try {
                audioRecord?.stop()
            } catch (_: Exception) {}
            Log.i("JARVIS", "[JARVIS][VOICE] WAKE_MIC_PAUSED_FOR_STT")
            Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=NONE")
        }
    }

    private fun resumeAudioCapture() {
        if (isPaused || audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            isPaused = false
            try {
                audioRecord?.startRecording()
            } catch (_: Exception) {}
            Log.i("JARVIS", "[JARVIS][VOICE] WAKE_MIC_RESUMED")
            Log.i("JARVIS", "[JARVIS][AUDIO] MIC_OWNER=WAKE_WORD")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop() {
        if (isRunning) return
        isRunning = true

        Log.i("JARVIS", "[JARVIS][VOICE] MIC_INIT_STARTED")
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            Log.i("JARVIS", "[JARVIS][VOICE] MIC_INITIALIZED sampleRate=$sampleRate channels=MONO encoding=PCM_16BIT bufferSize=$bufferSize")
            Log.i("JARVIS", "[JARVIS][VOICE] AUDIO_RECORD_STARTING")

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            Log.i("JARVIS", "[JARVIS][VOICE] AUDIO_RECORD_STATE=${audioRecord?.state} RECORDING_STATE=${audioRecord?.recordingState}")

            audioRecord?.startRecording()
            Log.i("JARVIS", "[JARVIS][VOICE] AUDIO_CAPTURE_STARTED")
            Log.i("JARVIS", "[JARVIS][VOICE] AUDIO_CAPTURE_ACTIVE")

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val shortBuffer = ShortArray(bufferSize / 2)
                while (isRunning && isActive) {
                    if (isPaused) {
                        delay(100)
                        continue
                    }
                    val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
                    if (read > 0) {
                        val bytes = read * 2
                        Log.i("JARVIS", "[JARVIS][VOICE] AUDIO_BUFFER_RECEIVED bytes=$bytes shorts=$read")
                        val frame = shortBuffer.copyOfRange(0, read)
                        val detected = controller.processAudioFrame(frame)
                        if (detected) {
                            Log.i("JARVIS", "[JARVIS][WAKE] WAKE_WORD_DETECTED")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "[JARVIS][VOICE] MIC_ERROR reason=${e.message}", e)
            logger.e("WakeWordService", "Failed to start AudioRecord for Wake Word service: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("JARVIS", "[JARVIS][VOICE] WAKE_SERVICE_COMMAND_RECEIVED")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        recordingJob?.cancel()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        controller.release()
        Log.i("JARVIS", "[JARVIS][VOICE] AUDIO_CAPTURE_STOPPED")
        logger.i("WakeWordService", "Foreground service destroyed. Wake Word engine released.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Offline Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Escuchando 'JARVIS' en segundo plano de forma local y privada."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Activo")
            .setContentText("Atento a la palabra de activación 'JARVIS' (Procesamiento Local)")
            .setSmallIcon(R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
