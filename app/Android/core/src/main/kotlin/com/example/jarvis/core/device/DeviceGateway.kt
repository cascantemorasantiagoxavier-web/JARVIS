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

/**
 * Acciones reales del sistema operativo. La implementación Android usa intents autorizados
 * (ACTION_DIAL, geo:, ACTION_SEND, ACTION_INSERT…), nunca acciones silenciosas.
 */
interface DeviceGateway {
    suspend fun battery(): BatteryInfo?
    suspend fun network(): NetworkInfo
    fun deviceInfo(): DeviceInfo
    suspend fun installedApps(): List<InstalledApp>
    suspend fun openApp(packageName: String): ActionResult
    suspend fun openSpecial(kind: SpecialApp): ActionResult
    suspend fun openLocation(lat: Double, lon: Double, label: String): ActionResult
    suspend fun directions(lat: Double, lon: Double, label: String): ActionResult
    suspend fun dial(number: String): ActionResult
    suspend fun share(text: String, title: String?): ActionResult
    suspend fun insertCalendarEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?): ActionResult
    suspend fun calendarEvents(from: LocalDateTime, to: LocalDateTime): CalendarRead
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
