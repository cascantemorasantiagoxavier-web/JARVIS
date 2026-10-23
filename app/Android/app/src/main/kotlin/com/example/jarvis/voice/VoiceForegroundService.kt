package com.example.jarvis.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.jarvis.JarvisApplication
import com.example.jarvis.MainActivity
import com.example.jarvis.R
import com.example.jarvis.reminders.Notifications

/**
 * Servicio en primer plano de tipo "microphone": mantiene el micrófono permitido mientras
 * JARVIS espera la palabra clave o conversa con la app en segundo plano. Android exige que se
 * inicie con la app visible; si el sistema o el fabricante (p. ej. HyperOS) lo detienen, se avisa.
 */
class VoiceForegroundService : Service() {
    enum class Mode { WAKE, SESSION }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voice = (application as JarvisApplication).container.voice
        if (intent?.action == ACTION_STOP) {
            voice.stopEverythingFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SESSION
        Notifications.createChannels(this)
        val notification = build(this, mode)
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) o SecurityException (Android 14+)
            voice.onServiceFailed(e.message)
            stopSelf()
            return START_NOT_STICKY
        }
        voice.onServiceRunning()
        // No se reinicia solo: Android 14 no permite reabrir el micrófono desde segundo plano.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as JarvisApplication).container.voice.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.example.jarvis.voice.STOP"
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: Mode) {
            val i = Intent(context, VoiceForegroundService::class.java).putExtra(EXTRA_MODE, mode.name)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        fun update(context: Context, mode: Mode) {
            if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, mode)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceForegroundService::class.java))
        }

        private fun build(context: Context, mode: Mode): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(
                context, 1, Intent(context, VoiceForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, Notifications.CHANNEL_VOICE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("J.A.R.V.I.S.")
                .setContentText(context.getString(if (mode == Mode.WAKE) R.string.notif_voice_listening else R.string.notif_voice_session))
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.notif_action_stop), stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
