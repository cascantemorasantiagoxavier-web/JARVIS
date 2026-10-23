package com.example.jarvis.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.jarvis.JarvisApplication
import com.example.jarvis.MainActivity
import com.example.jarvis.R
import com.example.jarvis.core.tasks.ReminderScheduleResult
import com.example.jarvis.core.tasks.ReminderScheduler
import com.example.jarvis.core.tasks.TaskItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

object Notifications {
    const val CHANNEL_VOICE = "jarvis_voice"
    const val CHANNEL_REMINDERS = "jarvis_reminders"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VOICE, context.getString(R.string.notif_channel_voice), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Indica cuándo el micrófono está activo para JARVIS."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.notif_channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Recordatorios de tareas creados con JARVIS."
            },
        )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/**
 * Avisos reales con AlarmManager. Si Android concede alarmas exactas, el aviso es puntual;
 * si no, se usa una alarma inexacta que el sistema puede retrasar (se informa al usuario).
 * Las alarmas se reprograman tras reiniciar el teléfono (BootReceiver).
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {
    private val am get() = context.getSystemService(AlarmManager::class.java)

    private fun pending(task: TaskItem?, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        if (task != null) intent.putExtra(EXTRA_TITLE, task.title).putExtra(EXTRA_DESC, task.description)
        return PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun schedule(task: TaskItem): ReminderScheduleResult {
        val at: LocalDateTime = task.reminderAt() ?: return ReminderScheduleResult.NotAllowed("la tarea no tiene fecha.")
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) return ReminderScheduleResult.NotAllowed("esa hora ya pasó.")
        val alarm = am ?: return ReminderScheduleResult.NotAllowed("el servicio de alarmas no está disponible.")
        val pi = pending(task, task.id)
        val notifWarning = if (Notifications.canPost(context)) "" else " Las notificaciones están desactivadas: actívalas para ver el aviso."
        val exactAllowed = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        return try {
            if (exactAllowed) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                if (notifWarning.isEmpty()) ReminderScheduleResult.Exact(at) else ReminderScheduleResult.Inexact(at, notifWarning.trim())
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                ReminderScheduleResult.Inexact(at, "Android no concedió alarmas exactas a JARVIS; puedes activarlas en Ajustes.$notifWarning")
            }
        } catch (e: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            ReminderScheduleResult.Inexact(at, "alarma exacta no permitida por el sistema.")
        }
    }

    override fun cancel(taskId: Long) {
        am?.cancel(pending(null, taskId))
    }

    companion object {
        const val EXTRA_ID = "task_id"
        const val EXTRA_TITLE = "task_title"
        const val EXTRA_DESC = "task_desc"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        /** Etiqueta propia: el id de la tarea no puede pisar otras notificaciones de la app. */
        const val TAG = "reminder"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmReminderScheduler.EXTRA_ID, -1)
        val title = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TITLE) ?: "Tienes un recordatorio"
        val desc = intent.getStringExtra(AlarmReminderScheduler.EXTRA_DESC).orEmpty()
        if (!Notifications.canPost(context)) return
        Notifications.createChannels(context)
        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, "tasks")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recordatorio de JARVIS")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (desc.isBlank()) title else "$title\n$desc"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, id.toInt(), n)
        } catch (e: SecurityException) {
            // Permiso de notificaciones revocado entre la comprobación y el aviso.
        }
    }
}

/** Reprograma los avisos pendientes tras reiniciar o actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext as? JarvisApplication ?: return result.finish()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.tasks.rescheduleAll()
            } finally {
                result.finish()
            }
        }
    }
}
