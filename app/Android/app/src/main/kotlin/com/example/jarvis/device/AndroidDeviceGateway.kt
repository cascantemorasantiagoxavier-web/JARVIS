package com.example.jarvis.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.jarvis.R
import com.example.jarvis.core.business.LatLon
import com.example.jarvis.core.device.ActionResult
import com.example.jarvis.core.device.BatteryInfo
import com.example.jarvis.core.device.CalendarEvent
import com.example.jarvis.core.device.CalendarRead
import com.example.jarvis.core.device.DeviceGateway
import com.example.jarvis.core.device.DeviceInfo
import com.example.jarvis.core.device.InstalledApp
import com.example.jarvis.core.device.NetworkInfo
import com.example.jarvis.core.device.SpecialApp
import com.example.jarvis.reminders.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Implementación Android de las acciones del dispositivo. Todas usan intents públicos que el
 * usuario completa (marcar, compartir, guardar evento): JARVIS nunca llama ni envía nada solo.
 */
class AndroidDeviceGateway(private val context: Context, private val permissions: PermissionBroker) : DeviceGateway {

    override suspend fun battery(): BatteryInfo? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct < 0 || pct > 100) return null
        return BatteryInfo(pct, bm.isCharging)
    }

    override suspend fun network(): NetworkInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkInfo(false, false, "desconocido")
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkInfo(false, false, "ninguna")
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "otra red"
        }
        return NetworkInfo(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            type = type,
        )
    }

    override fun deviceInfo() = DeviceInfo(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)

    @Suppress("DEPRECATION")
    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        list.map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun openApp(packageName: String): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failed("La aplicación no está instalada o no se puede abrir.")
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
        return launch(intent, label)
    }

    override suspend fun openSpecial(kind: SpecialApp): ActionResult {
        val intent = when (kind) {
            SpecialApp.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            SpecialApp.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            SpecialApp.DIALER -> Intent(Intent.ACTION_DIAL)
            SpecialApp.CALCULATOR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            SpecialApp.CALENDAR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            SpecialApp.BROWSER -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        }
        return launch(intent, kind.aliases.first())
    }

    override suspend fun openLocation(lat: Double, lon: Double, label: String): ActionResult {
        val q = URLEncoder.encode(label, "UTF-8")
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($q)")), "la ubicación")
    }

    override suspend fun directions(lat: Double, lon: Double, label: String): ActionResult =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")), "la ruta")

    override suspend fun dial(number: String): ActionResult {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.length < 8) return ActionResult.Failed("El número \"$number\" no parece válido.")
        return launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", clean, null)), "el marcador")
    }

    override suspend fun share(text: String, title: String?): ActionResult {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (title != null) send.putExtra(Intent.EXTRA_SUBJECT, title)
        return launch(Intent.createChooser(send, title ?: "Compartir"), "compartir")
    }

    override suspend fun insertCalendarEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?): ActionResult {
        val zone = ZoneId.systemDefault()
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, (end ?: start.plusHours(1)).atZone(zone).toInstant().toEpochMilli())
        if (location != null) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        return launch(intent, "el calendario")
    }

    override suspend fun calendarEvents(from: LocalDateTime, to: LocalDateTime): CalendarRead = withContext(Dispatchers.IO) {
        if (!permissions.isGranted(Manifest.permission.READ_CALENDAR)) return@withContext CalendarRead.PermissionMissing
        val zone = ZoneId.systemDefault()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.atZone(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, to.atZone(zone).toInstant().toEpochMilli())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && events.size < 50) {
                val begin = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), zone)
                val end = if (c.isNull(2)) null else LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), zone)
                events += CalendarEvent(c.getString(0) ?: "(sin título)", begin, end, c.getInt(3) == 1, c.getString(4)?.takeIf { it.isNotBlank() })
            }
        }
        CalendarRead.Events(events)
    }

    /** Ubicación aproximada para "cerca de mí" (pide el permiso solo en ese momento). */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LatLon? {
        if (!permissions.request(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val best: Location? = withContext(Dispatchers.IO) {
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }
        // Una posición de hace horas no sirve para "cerca de mí": se pide una nueva (máx. 10 s).
        val recent = best?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
        val fix = recent ?: (if (Build.VERSION.SDK_INT >= 30) currentFix(lm) else null) ?: best
        return fix?.let { LatLon(it.latitude, it.longitude) }
    }

    @RequiresApi(30)
    @SuppressLint("MissingPermission")
    private suspend fun currentFix(lm: LocationManager): Location? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /**
     * Abre una actividad. Desde segundo plano Android lo impide: en ese caso se deja una
     * notificación que el usuario puede tocar (comportamiento permitido por el sistema).
     */
    private suspend fun launch(intent: Intent, label: String): ActionResult = withContext(Dispatchers.Main) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!ForegroundTracker.isForeground) {
            val pi = PendingIntent.getActivity(context, label.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("JARVIS")
                .setContentText("Toca para abrir $label")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(NotificationManager::class.java)
            return@withContext if (nm != null && nm.areNotificationsEnabled()) {
                nm.notify(label.hashCode(), n)
                ActionResult.Done("Android no permite abrir $label con JARVIS en segundo plano; te dejé una notificación para abrirlo.")
            } else {
                ActionResult.Failed("Android no permite abrir $label en segundo plano y las notificaciones están desactivadas.")
            }
        }
        try {
            context.startActivity(intent)
            ActionResult.Done("")
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No hay ninguna aplicación instalada que pueda abrir $label.")
        } catch (e: SecurityException) {
            ActionResult.Failed("Android no permitió abrir $label.")
        }
    }
}
