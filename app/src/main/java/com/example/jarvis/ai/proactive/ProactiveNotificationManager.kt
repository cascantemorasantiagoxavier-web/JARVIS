package com.example.jarvis.ai.proactive

import android.R
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.jarvis.MainActivity
import com.example.jarvis.core.logging.AppLogger
import com.example.jarvis.core.logging.ConsoleLogger
import com.example.jarvis.domain.proactive.ProactiveDecision
import com.example.jarvis.domain.proactive.ProactivePriority
import com.example.jarvis.domain.proactive.ProactiveSuggestion

class ProactiveNotificationManager(
    private val context: Context,
    private val logger: AppLogger = ConsoleLogger()
) {
    companion object {
        private const val CHANNEL_ID = "jarvis_proactive_suggestions_channel"
        private const val CHANNEL_NAME = "JARVIS Proactive Suggestions"
        private const val TAG = "JARVIS"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de sugerencias e iniciativas proactivas de alta prioridad de JARVIS."
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            Log.i(TAG, "[JARVIS][NOTIFY] CHANNEL_CREATED id=$CHANNEL_ID")
        }
    }

    @SuppressLint("MissingPermission")
    fun showProactiveSuggestionNotification(suggestion: ProactiveSuggestion): Boolean {
        if (suggestion.priority != ProactivePriority.HIGH && suggestion.priority != ProactivePriority.URGENT && suggestion.decision != ProactiveDecision.NOTIFY) {
            logger.i("ProactiveNotificationManager", "Suppressed non-high priority suggestion notification: ${suggestion.id}")
            return false
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("suggestionId", suggestion.id)
            putExtra("action", suggestion.actionPayload["action"] ?: "view")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            suggestion.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("JARVIS — ${suggestion.title}")
            .setContentText(suggestion.message)
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notifId = suggestion.id.hashCode()
        return try {
            manager?.notify(notifId, notification)
            Log.i(TAG, "[JARVIS][NOTIFY] NOTIFICATION_SENT")
            Log.i(TAG, "[JARVIS][NOTIFY] NOTIFICATION_ID=$notifId")
            logger.i("ProactiveNotificationManager", "Posted native notification for high priority suggestion: ${suggestion.title}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[JARVIS][NOTIFY] NOTIFICATION_ERROR=${e.message}", e)
            logger.e("ProactiveNotificationManager", "Failed to post notification", e)
            false
        }
    }
}
