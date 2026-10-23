package com.example.jarvis.core.device

import java.time.LocalDateTime

data class BatteryInfo(val percent: Int, val charging: Boolean)

data class NetworkInfo(val connected: Boolean, val validated: Boolean, val type: String) {
    val label: String
        get() = when {
            !connected -> "Sin conexión"
            !validated -> "Conectado ($type) sin acceso verificado a Internet"
            else -> "Conectado por $type"
        }
}

data class DeviceInfo(val manufacturer: String, val model: String, val androidVersion: String, val sdk: Int)

data class InstalledApp(val label: String, val packageName: String)

sealed interface ActionResult {
    data class Done(val message: String) : ActionResult
    data class Failed(val reason: String) : ActionResult
}

data class CalendarEvent(val title: String, val start: LocalDateTime, val end: LocalDateTime?, val allDay: Boolean, val location: String?)

sealed interface CalendarRead {
    data class Events(val events: List<CalendarEvent>) : CalendarRead
    data object PermissionMissing : CalendarRead
}

/** Diagnóstico ampliado: solo datos que Android permite leer sin permisos especiales. */
data class DeviceDiagnostics(
    val storageFreeMb: Long?,
    val storageTotalMb: Long?,
    val notificationsEnabled: Boolean?,
    val exactAlarmsAllowed: Boolean?,
    val batteryOptimizationIgnored: Boolean?,
    val permissions: Map<String, Boolean>,
    val locale: String,
    val timeZone: String,
)

enum class MessageChannel { SMS, WHATSAPP, ANY }

sealed interface SaveResult {
    data class Saved(val uri: String, val location: String) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * Lecturas del dispositivo sin efectos. Las habilidades solo reciben esta vista: cualquier acción
 * (abrir, compartir, redactar, guardar archivos) pasa por herramientas con política de riesgo.
 */
interface DeviceInfoReader {
    suspend fun battery(): BatteryInfo?
    suspend fun network(): NetworkInfo
    fun deviceInfo(): DeviceInfo
    suspend fun installedApps(): List<InstalledApp>
    suspend fun calendarEvents(from: LocalDateTime, to: LocalDateTime): CalendarRead
    suspend fun diagnostics(): DeviceDiagnostics
}

/**
 * Acciones reales del sistema operativo. La implementación Android usa intents autorizados
 * (ACTION_DIAL, geo:, ACTION_SEND, ACTION_INSERT…), nunca acciones silenciosas.
 */
interface DeviceGateway : DeviceInfoReader {
    suspend fun openApp(packageName: String): ActionResult
    suspend fun openSpecial(kind: SpecialApp): ActionResult
    suspend fun openLocation(lat: Double, lon: Double, label: String): ActionResult
    suspend fun directions(lat: Double, lon: Double, label: String): ActionResult
    suspend fun dial(number: String): ActionResult
    suspend fun share(text: String, title: String?): ActionResult
    suspend fun insertCalendarEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?): ActionResult
    /** Abre la app de correo con un borrador (mailto:). Nunca envía. */
    suspend fun composeEmail(to: List<String>, subject: String?, body: String): ActionResult
    /** Abre SMS/WhatsApp con el texto preparado. Nunca envía. */
    suspend fun composeMessage(channel: MessageChannel, phone: String?, text: String): ActionResult
    /** Guarda un documento generado en Descargas/JARVIS (o en el almacenamiento de la app). */
    suspend fun saveDocument(fileName: String, mimeType: String, content: ByteArray): SaveResult
    suspend fun openDocument(uri: String): ActionResult
}

/** Apps del sistema que se abren por intent y no por nombre de paquete. */
enum class SpecialApp(val aliases: List<String>) {
    CAMERA(listOf("camara", "camera")),
    SETTINGS(listOf("ajustes", "configuracion", "settings")),
    DIALER(listOf("telefono", "marcador")),
    CALCULATOR(listOf("calculadora")),
    CALENDAR(listOf("calendario", "agenda")),
    BROWSER(listOf("navegador")),
}
