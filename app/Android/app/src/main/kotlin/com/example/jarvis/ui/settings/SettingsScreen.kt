package com.example.jarvis.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.jarvis.AppContainer
import com.example.jarvis.BuildConfig
import com.example.jarvis.core.device.InstalledApp
import com.example.jarvis.core.realtime.PriceTable
import com.example.jarvis.core.weather.Place
import com.example.jarvis.data.settings.ConnectMode
import com.example.jarvis.data.settings.CoreAccent
import com.example.jarvis.data.settings.VoiceEngine
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlinx.coroutines.launch
import java.time.LocalDate

private val REALTIME_MODELS = listOf("gpt-realtime-2.1-mini", "gpt-realtime-2.1")
private val VOICES = listOf("cedar", "marin", "ash", "echo", "verse", "ballad", "alloy", "coral", "sage", "shimmer")

@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, notify: (String) -> Unit) {
    val hud = LocalHud.current
    val context = LocalContext.current
    val s by c.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.backendUrl) }
    var token by remember { mutableStateOf("") }
    var connection by remember { mutableStateOf<String?>(null) }
    var cityQuery by remember { mutableStateOf("") }
    var cityResults by remember { mutableStateOf<List<Place>>(emptyList()) }
    var defaultZone by remember { mutableStateOf(s.defaultLocation.orEmpty()) }
    var ttsVoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingApps by remember { mutableStateOf(false) }
    val wake = c.voice.wakeAvailability
    fun update(f: (com.example.jarvis.data.settings.AppSettings) -> com.example.jarvis.data.settings.AppSettings) = scope.launch { c.settings.update(f) }

    LaunchedEffect(Unit) { ttsVoices = runCatching { c.localSpeech.spanishVoices().map { it.name } }.getOrDefault(emptyList()) }

    ScreenScaffold("Ajustes", onBack) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                HudPanel(Modifier.fillMaxWidth().padding(top = 8.dp), title = "Servidor de JARVIS") {
                    Text("El servidor guarda la clave de OpenAI; la app solo usa este token propio. Ver docs/SETUP.md.", style = HudText.bodySmall, color = hud.textDim)
                    OutlinedTextField(url, { url = it }, label = { Text("URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(
                        token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                        label = { Text(if (c.secrets.hasToken()) "Token (guardado; escribe para reemplazar)" else "Token de la app (JARVIS_CLIENT_TOKEN)") },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("Guardar", {
                            scope.launch {
                                c.settings.update { it.copy(backendUrl = url.trim()) }
                                if (token.isNotBlank()) { c.secrets.saveToken(token); token = "" }
                                notify(if (c.backendConfigured()) "Servidor configurado." else "Falta una URL válida o un token de al menos 24 caracteres.")
                            }
                        })
                        HudChip("Probar conexión", {
                            connection = "Probando…"
                            scope.launch {
                                connection = runCatching { c.backend.health() }.fold(
                                    { h -> "En línea · voz ${if (h.features.realtime) "✓ (${h.features.realtimeModel})" else "✗ sin clave de OpenAI"} · chat ${if (h.features.chat) "✓" else "✗"} · web: ${h.features.webSearch.joinToString().ifBlank { "ninguno" }}" },
                                    { e -> "Sin conexión: ${(e as? com.example.jarvis.core.net.BackendException)?.userMessage ?: e.message}" },
                                )
                                if (c.backendConfigured()) runCatching { c.backend.usage() }.onSuccess { u ->
                                    connection += "\nServidor hoy: ${u.realtime.today}/${u.realtime.maxPerDay ?: "?"} sesiones de voz · ${u.chat.today}/${u.chat.maxPerDay ?: "?"} peticiones de chat"
                                }
                            }
                        })
                    }
                    connection?.let { Text(it, style = HudText.bodySmall, color = hud.text) }
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Voz") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("GPT Realtime", { update { it.copy(voiceEngine = VoiceEngine.REALTIME) } }, selected = s.voiceEngine == VoiceEngine.REALTIME)
                        HudChip("Voz local (gratis)", { update { it.copy(voiceEngine = VoiceEngine.LOCAL) } }, selected = s.voiceEngine == VoiceEngine.LOCAL)
                    }
                    if (s.voiceEngine == VoiceEngine.REALTIME) {
                        Text("Modelo", style = HudText.label, color = hud.textDim, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            REALTIME_MODELS.forEach { m -> HudChip(m, { update { it.copy(realtimeModel = m) } }, selected = s.realtimeModel == m) }
                        }
                        Text("Voz (cedar: timbre grave y sereno; ninguna es la voz de las películas)", style = HudText.label, color = hud.textDim, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            VOICES.forEach { v -> HudChip(v, { update { it.copy(realtimeVoice = v) } }, selected = s.realtimeVoice == v) }
                        }
                        Text("Velocidad: ${"%.2f".format(s.realtimeSpeed)}×", style = HudText.label, color = hud.textDim, modifier = Modifier.padding(top = 8.dp))
                        Slider(s.realtimeSpeed.toFloat(), { v -> update { it.copy(realtimeSpeed = (v * 20).toInt() / 20.0) } }, valueRange = 0.8f..1.3f)
                        Text("Cerrar tras ${s.idleTimeoutSeconds} s de silencio", style = HudText.label, color = hud.textDim)
                        Slider(s.idleTimeoutSeconds.toFloat(), { v -> update { it.copy(idleTimeoutSeconds = v.toInt()) } }, valueRange = 10f..120f)
                        Text("Duración máxima por sesión: ${s.maxSessionMinutes} min", style = HudText.label, color = hud.textDim)
                        Slider(s.maxSessionMinutes.toFloat(), { v -> update { it.copy(maxSessionMinutes = v.toInt()) } }, valueRange = 2f..30f)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Conexión", style = HudText.label, color = hud.textDim)
                            HudChip("Unificada (recomendada)", { update { it.copy(connectMode = ConnectMode.UNIFIED) } }, selected = s.connectMode == ConnectMode.UNIFIED)
                            HudChip("Credencial efímera", { update { it.copy(connectMode = ConnectMode.EPHEMERAL) } }, selected = s.connectMode == ConnectMode.EPHEMERAL)
                        }
                        val usage = if (s.usageDay == LocalDate.now().toString()) s.usageToday else null
                        val prices = PriceTable.forModel(s.realtimeModel)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (usage == null) "Uso de voz hoy: sin sesiones." else
                                "Uso de voz hoy: ${usage.sessions} sesiones, ${usage.seconds / 60} min, ${usage.audioIn + usage.audioOut} tokens de audio · costo estimado ≈ US$ ${"%.3f".format(usage.toUsage().estimatedCostUsd(prices))}",
                            style = HudText.bodySmall, color = hud.text,
                        )
                        Text("Estimación con precios públicos no verificados desde la app; el gasto real se ve en el panel de OpenAI.", style = HudText.bodySmall, color = hud.textFaint)
                    }
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Palabra de activación \"JARVIS\"") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(s.wakeWordEnabled, { v -> scope.launch { c.voice.setWakeWordEnabled(v) } }, enabled = wake.available || s.wakeWordEnabled)
                        Spacer(Modifier.padding(4.dp))
                        Text(if (s.wakeWordEnabled) "Escucha local activa" else "Desactivada", style = HudText.body, color = hud.text)
                    }
                    if (!wake.available) Text(wake.reason ?: "", style = HudText.bodySmall, color = hud.warn)
                    Text("Sensibilidad: ${"%.0f".format(s.wakeSensitivity * 100)}%", style = HudText.label, color = hud.textDim, modifier = Modifier.padding(top = 6.dp))
                    Slider(s.wakeSensitivity, { v -> update { it.copy(wakeSensitivity = v) } }, valueRange = 0.1f..0.9f)
                    Text(
                        "La palabra clave se detecta en el teléfono, sin enviar audio. Android puede detener la escucha por ahorro de batería: " +
                            "en Xiaomi/HyperOS activa \"Inicio automático\" y quita las restricciones de batería para JARVIS. No hay garantía de escucha permanente.",
                        style = HudText.bodySmall, color = hud.textFaint,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("Optimización de batería", { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } })
                        HudChip("Ajustes de la app", { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) } })
                        if (Build.VERSION.SDK_INT >= 31) {
                            val am = context.getSystemService(AlarmManager::class.java)
                            HudChip(if (am?.canScheduleExactAlarms() == true) "Alarmas exactas ✓" else "Permitir alarmas exactas", {
                                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))) }
                            })
                        }
                    }
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Voz local (sin costo)") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.localContinuous, { v -> update { it.copy(localContinuous = v) } })
                        Text("Seguir escuchando después de responder", style = HudText.bodySmall, color = hud.text)
                    }
                    Text("Voz del sistema", style = HudText.label, color = hud.textDim)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("Predeterminada", { update { it.copy(ttsVoiceName = null) } }, selected = s.ttsVoiceName == null)
                        ttsVoices.forEach { v -> HudChip(v, { update { it.copy(ttsVoiceName = v) } }, selected = s.ttsVoiceName == v) }
                    }
                    Text("Velocidad ${"%.2f".format(s.ttsRate)} · Tono ${"%.2f".format(s.ttsPitch)}", style = HudText.label, color = hud.textDim, modifier = Modifier.padding(top = 6.dp))
                    Slider(s.ttsRate, { v -> update { it.copy(ttsRate = v) } }, valueRange = 0.6f..1.4f)
                    Slider(s.ttsPitch, { v -> update { it.copy(ttsPitch = v) } }, valueRange = 0.6f..1.3f)
                    HudChip("Probar voz", {
                        scope.launch { c.localSpeech.speak("Siempre a tu lado, jefe.", s.ttsVoiceName, s.ttsRate, s.ttsPitch) }
                    })
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Ubicación y clima") {
                    Text("Ciudad del clima: ${s.weatherPlace?.label ?: "sin configurar"}", style = HudText.body, color = hud.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(cityQuery, { cityQuery = it }, label = { Text("Buscar ciudad (ej.: Acosta)") }, singleLine = true, modifier = Modifier.weight(1f))
                        HudChip("Buscar", {
                            scope.launch {
                                cityResults = runCatching { c.weather.geocode(cityQuery) }.getOrElse { notify("No se pudo buscar: ${it.message}"); emptyList() }
                                if (cityResults.isEmpty()) notify("Sin resultados para \"$cityQuery\".")
                            }
                        })
                    }
                    cityResults.forEach { p -> HudChip("${p.label}${p.country?.let { ", $it" } ?: ""}", { update { it.copy(weatherPlace = p) }; cityResults = emptyList() }, modifier = Modifier.padding(top = 4.dp)) }
                    Text("Clima: Open-Meteo.com (CC BY 4.0), consultado directamente desde el teléfono.", style = HudText.bodySmall, color = hud.textFaint)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(defaultZone, { defaultZone = it }, label = { Text("Zona por defecto para negocios") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    HudChip("Guardar zona", { update { it.copy(defaultLocation = defaultZone.trim().ifBlank { null }) } })
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Interfaz") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("Ámbar", { update { it.copy(accent = CoreAccent.AMBER) } }, selected = s.accent == CoreAccent.AMBER)
                        HudChip("Cian", { update { it.copy(accent = CoreAccent.CYAN) } }, selected = s.accent == CoreAccent.CYAN)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.reduceMotion, { v -> update { it.copy(reduceMotion = v) } })
                        Text("Reducir animaciones (ahorra batería)", style = HudText.bodySmall, color = hud.text)
                    }
                    Text("Estrategia por defecto del chat", style = HudText.label, color = hud.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("AUTO", "FAST", "BALANCED", "DEEP").forEach { m -> HudChip(m, { update { it.copy(defaultMode = m) } }, selected = s.defaultMode == m) }
                    }
                    HudChip("Elegir apps favoritas", { editingApps = true }, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Datos") {
                    HudChip("Borrar historial de conversación", { scope.launch { c.orchestrator.clearHistory(); notify("Historial borrado. La memoria y los prospectos se conservan.") } })
                    Text("La memoria, tareas y prospectos se gestionan en sus pantallas. Nada se copia a la nube.", style = HudText.bodySmall, color = hud.textFaint)
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth().padding(bottom = 24.dp), title = "Acerca de") {
                    Text("J.A.R.V.I.S. ${BuildConfig.VERSION_NAME} · asistente personal inspirado en Iron Man (proyecto personal, sin afiliación con Marvel).", style = HudText.bodySmall, color = hud.text)
                    Text(
                        "Datos de negocios y mapas © colaboradores de OpenStreetMap (ODbL). Globo: Natural Earth (dominio público). Clima: Open-Meteo (CC BY 4.0). " +
                            "Fuentes Orbitron y Rajdhani (SIL OFL 1.1). WebRTC (BSD-3). osmdroid (Apache-2.0). La voz es una voz sintética de OpenAI o del sistema, no la de las películas.",
                        style = HudText.bodySmall, color = hud.textFaint,
                    )
                }
            }
        }
    }

    if (editingApps) FavoriteAppsDialog(c, s.favoriteApps, onDismiss = { editingApps = false }) { list ->
        update { it.copy(favoriteApps = list) }
        editingApps = false
    }
}

@Composable
private fun FavoriteAppsDialog(c: AppContainer, current: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var chosen by remember { mutableStateOf(current.toSet()) }
    LaunchedEffect(Unit) { apps = c.device.installedApps() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps favoritas (máx. 15)") },
        text = {
            LazyColumn(Modifier.height(380.dp)) {
                items(apps.size) { i ->
                    val a = apps[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(a.packageName in chosen, { v -> chosen = if (v && chosen.size < 15) chosen + a.packageName else chosen - a.packageName })
                        Column { Text(a.label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current.filter { it in chosen } + chosen.filter { it !in current }) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
