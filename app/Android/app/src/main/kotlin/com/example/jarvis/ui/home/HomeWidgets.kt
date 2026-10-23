package com.example.jarvis.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jarvis.core.device.SpecialApp
import com.example.jarvis.core.realtime.VoiceState
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.tools.local.SpanishDates
import com.example.jarvis.core.weather.WeatherKind
import com.example.jarvis.data.db.ToolLogEntity
import com.example.jarvis.ui.components.ChamferShape
import com.example.jarvis.ui.components.Corner
import com.example.jarvis.ui.components.HudButton
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.hudFrame
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Emblema propio de JARVIS (no reproduce logotipos de terceros). */
@Composable
fun JarvisEmblem(modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * 0.92f
        drawCircle(hud.primary.copy(alpha = 0.18f), r, c)
        drawCircle(hud.primary, r, c, style = Stroke(1.6.dp.toPx()))
        for (i in 0 until 6) {
            val a = (i * 60f + 30f) * PI.toFloat() / 180f
            val p1 = Offset(c.x + cos(a) * r * 0.35f, c.y + sin(a) * r * 0.35f)
            val p2 = Offset(c.x + cos(a + 1.05f) * r * 0.82f, c.y + sin(a + 1.05f) * r * 0.82f)
            drawLine(hud.text.copy(alpha = 0.9f), p1, p2, strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(hud.bright, r * 0.22f, c)
    }
}

@Composable
fun BrandPanel(modifier: Modifier = Modifier, compact: Boolean = false) {
    val hud = LocalHud.current
    Row(
        modifier.hudFrame(cut = 16.dp, corners = setOf(Corner.TopLeft, Corner.BottomRight), accent = hud.primary, line = hud.line, fill = hud.panel).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JarvisEmblem(Modifier.size(if (compact) 34.dp else 44.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("JARVIS", style = HudText.brand.copy(fontSize = if (compact) 20.sp else 28.sp), color = hud.text, maxLines = 1)
            if (!compact) Text("Siempre a tu lado, jefe.", style = HudText.bodySmall, color = hud.textDim)
        }
    }
}

/** Forma hexagonal alargada del reloj central. */
private class HexShape : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val c = size.height * 0.32f
        return androidx.compose.ui.graphics.Outline.Generic(Path().apply {
            moveTo(c, 0f); lineTo(size.width - c, 0f); lineTo(size.width, size.height / 2); lineTo(size.width - c, size.height)
            lineTo(c, size.height); lineTo(0f, size.height / 2); close()
        })
    }
}

@Composable
fun ClockPanel(modifier: Modifier = Modifier, compact: Boolean = false) {
    val hud = LocalHud.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000L - (System.currentTimeMillis() % 1000L)) } }
    val shape = remember { HexShape() }
    Box(
        modifier
            .semantics { contentDescription = "Hora ${SpanishDates.spokenTime(now.toLocalTime())}" }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(hud.panel, Color.Black)))
            .padding(horizontal = 26.dp, vertical = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val o = shape.createOutline(size, layoutDirection, this)
            drawOutline(o, hud.primary, style = Stroke(1.5.dp.toPx()))
            drawLine(hud.bright, Offset(size.width * 0.06f, size.height * 0.32f), Offset(size.width * 0.06f, size.height * 0.68f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(hud.bright, Offset(size.width * 0.94f, size.height * 0.32f), Offset(size.width * 0.94f, size.height * 0.68f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(SpanishDates.clock(now.toLocalTime()), style = HudText.clock.copy(fontSize = if (compact) 22.sp else 34.sp), color = hud.text)
            Text(SpanishDates.headerDate(now.toLocalDate()), style = HudText.bodySmall, color = hud.textDim)
        }
    }
}

fun weatherIcon(k: WeatherKind): ImageVector = when (k) {
    WeatherKind.CLEAR -> Icons.Outlined.WbSunny
    WeatherKind.PARTLY_CLOUDY -> Icons.Outlined.WbCloudy
    WeatherKind.CLOUDY -> Icons.Outlined.Cloud
    WeatherKind.FOG -> Icons.Outlined.Dehaze
    WeatherKind.DRIZZLE, WeatherKind.RAIN -> Icons.Outlined.Umbrella
    WeatherKind.STORM -> Icons.Outlined.FlashOn
    WeatherKind.SNOW -> Icons.Outlined.AcUnit
}

@Composable
fun WeatherPanel(weather: WeatherUi, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val hud = LocalHud.current
    Row(
        modifier
            .hudFrame(cut = 16.dp, corners = setOf(Corner.TopRight, Corner.BottomLeft), accent = hud.primary, line = hud.line, fill = hud.panel)
            .clip(ChamferShape(16.dp, setOf(Corner.TopRight, Corner.BottomLeft)))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (weather) {
            is WeatherUi.Data -> {
                Icon(weatherIcon(weather.weather.kind), contentDescription = weather.weather.description, tint = hud.bright, modifier = Modifier.size(if (compact) 28.dp else 40.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${"%.0f".format(weather.weather.temperatureC)}°C", style = HudText.clock.copy(fontSize = if (compact) 16.sp else 22.sp), color = hud.text)
                    Text(weather.weather.place.label, style = HudText.bodySmall, color = hud.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            WeatherUi.Loading -> Text("Consultando clima…", style = HudText.bodySmall, color = hud.textDim)
            WeatherUi.NotConfigured -> Column {
                Text("CLIMA", style = HudText.label, color = hud.bright)
                Text("Toca para elegir tu ciudad", style = HudText.bodySmall, color = hud.textDim)
            }
            is WeatherUi.Error -> Column {
                Text("CLIMA NO DISPONIBLE", style = HudText.label, color = hud.warn)
                Text(weather.message, style = HudText.bodySmall, color = hud.textDim, maxLines = 2)
            }
        }
    }
}

@Composable
fun AppsPanel(apps: List<AppTile>, onLaunch: (AppTile) -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier, columns: Int = 3) {
    val hud = LocalHud.current
    HudPanel(modifier, title = "Aplicaciones", corners = setOf(Corner.TopLeft, Corner.BottomRight)) {
        if (apps.isEmpty()) {
            Text("Ninguna de tus apps favoritas está instalada. Toca para elegirlas.", style = HudText.bodySmall, color = hud.textDim, modifier = Modifier.clickable(onClick = onEdit))
        } else {
            LazyVerticalGrid(GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                items(apps, key = { it.target }) { tile ->
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onLaunch(tile) }.padding(4.dp).semantics { contentDescription = "Abrir ${tile.label}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16100A)), contentAlignment = Alignment.Center) {
                            if (tile.icon != null) Image(tile.icon, contentDescription = null, modifier = Modifier.size(34.dp))
                            else Icon(specialIcon(tile.target), null, tint = hud.text, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(tile.label, style = HudText.bodySmall.copy(fontSize = 12.sp), color = hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private fun specialIcon(target: String): ImageVector = when (target.removePrefix("special:")) {
    SpecialApp.CAMERA.name -> Icons.Outlined.CameraAlt
    SpecialApp.SETTINGS.name -> Icons.Outlined.Settings
    SpecialApp.CALCULATOR.name -> Icons.Outlined.Calculate
    SpecialApp.CALENDAR.name -> Icons.Outlined.CalendarMonth
    SpecialApp.DIALER.name -> Icons.Outlined.Phone
    else -> Icons.Outlined.Apps
}

private fun toolIcon(tool: String): ImageVector = when {
    tool.startsWith("business") || tool.contains("prospect") -> Icons.Outlined.Storefront
    tool == "web_search" || tool == "fetch_page" -> Icons.Outlined.TravelExplore
    tool.contains("task") || tool == "plan_day" -> Icons.Outlined.TaskAlt
    tool in setOf("remember", "recall", "forget", "update_memory", "search_notes") -> Icons.Outlined.Memory
    tool in setOf("show_on_map", "open_location", "get_directions") -> Icons.Outlined.Map
    tool == "get_weather" -> Icons.Outlined.WbSunny
    tool == "open_app" -> Icons.Outlined.Apps
    tool == "call_phone" -> Icons.Outlined.Phone
    else -> Icons.Outlined.Widgets
}

private fun toolTitle(tool: String): String = when (tool) {
    "business_search" -> "Búsqueda de negocios"
    "business_details", "business_verify" -> "Ficha de negocio"
    "web_search" -> "Búsqueda web"
    "fetch_page" -> "Lectura de página"
    "remember" -> "Memoria guardada"
    "recall" -> "Consulta de memoria"
    "search_notes" -> "Notas de Obsidian"
    "create_task" -> "Tarea creada"
    "list_tasks", "plan_day" -> "Agenda"
    "get_weather" -> "Clima"
    "open_app" -> "Aplicación"
    "show_on_map", "open_location", "get_directions" -> "Mapa"
    "save_prospect", "list_prospects", "update_prospect", "organize_prospects" -> "Prospectos"
    "calculate", "convert_units" -> "Cálculo"
    "get_datetime" -> "Fecha y hora"
    "get_device_status" -> "Diagnóstico"
    else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun ActivityPanel(items: List<ToolLogEntity>, onOpenChat: () -> Unit, onShortcut: (String) -> Unit, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier, title = "Últimas actividades", corners = setOf(Corner.TopLeft, Corner.BottomRight), onClick = onOpenChat) {
        if (items.isEmpty()) {
            Text("Sin actividad todavía.", style = HudText.bodySmall, color = hud.textDim)
            Spacer(Modifier.height(8.dp))
            listOf("business" to "Buscar negocios", "web" to "Búsqueda web", "tasks" to "Ver tareas").forEach { (route, label) ->
                Text("› $label", style = HudText.body, color = hud.bright, modifier = Modifier.clickable { onShortcut(route) }.padding(vertical = 4.dp))
            }
        } else {
            items.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF16100A)), contentAlignment = Alignment.Center) {
                        Icon(toolIcon(e.tool), null, tint = if (e.ok) hud.bright else hud.error, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(toolTitle(e.tool), style = HudText.strong.copy(fontSize = 14.sp), color = hud.text, maxLines = 1)
                        Text(e.summary, style = HudText.bodySmall, color = if (e.ok) hud.textDim else hud.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Casco estilizado de diseño propio, inspirado en la estética de la armadura (no es un logotipo oficial). */
@Composable
fun AssistantPanel(voice: VoiceState, level: Float, statusLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier, corners = setOf(Corner.TopLeft, Corner.BottomRight), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(64.dp)) {
                val w = size.width; val h = size.height
                val helmet = Path().apply {
                    moveTo(w * 0.5f, h * 0.04f)
                    cubicTo(w * 0.86f, h * 0.06f, w * 0.94f, h * 0.36f, w * 0.88f, h * 0.62f)
                    lineTo(w * 0.70f, h * 0.94f); lineTo(w * 0.30f, h * 0.94f); lineTo(w * 0.12f, h * 0.62f)
                    cubicTo(w * 0.06f, h * 0.36f, w * 0.14f, h * 0.06f, w * 0.5f, h * 0.04f); close()
                }
                drawPath(helmet, hud.primary.copy(alpha = 0.12f))
                drawPath(helmet, hud.primary, style = Stroke(1.6.dp.toPx()))
                val eyeY = h * 0.45f
                drawLine(hud.bright, Offset(w * 0.24f, eyeY), Offset(w * 0.43f, eyeY + h * 0.03f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(hud.bright, Offset(w * 0.76f, eyeY), Offset(w * 0.57f, eyeY + h * 0.03f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(hud.primary, Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.32f), strokeWidth = 1.2.dp.toPx())
                drawLine(hud.primary, Offset(w * 0.36f, h * 0.76f), Offset(w * 0.64f, h * 0.76f), strokeWidth = 1.2.dp.toPx())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("JARVIS", style = HudText.title, color = hud.bright)
                Text("Tu asistente de IA personal", style = HudText.bodySmall, color = hud.textDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1A0F05))) {
            Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(6.dp).background(Brush.horizontalGradient(listOf(hud.deep, hud.bright))))
        }
        Spacer(Modifier.height(4.dp))
        Text(statusLabel, style = HudText.label, color = hud.textDim, maxLines = 1)
    }
}

@Composable
fun SystemStatusPanel(status: SystemStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier, corners = setOf(Corner.TopLeft, Corner.BottomRight), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(56.dp).semantics { contentDescription = "Batería ${status.battery ?: "desconocida"} por ciento" }) {
                val stroke = 3.dp.toPx()
                drawArc(hud.line.copy(alpha = 0.35f), 135f, 270f, false, Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                val pct = (status.battery ?: 0) / 100f
                drawArc(hud.bright, 135f, 270f * pct, false, Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(hud.primary, 3.dp.toPx())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                listOf("Tiempo" to status.time, "Ubicación" to status.location, "Aplicaciones" to status.apps, "Internet" to status.internet, "Sistema" to status.system).forEach { (label, ok) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(label.uppercase(), style = HudText.label.copy(fontSize = 9.sp), color = if (ok) hud.text else hud.textFaint, modifier = Modifier.width(92.dp))
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (ok) hud.primary else hud.line.copy(alpha = 0.2f)))
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${status.battery?.let { "Batería $it% · " } ?: ""}${status.backendLabel}", style = HudText.label.copy(fontSize = 9.sp), color = hud.textDim, maxLines = 1)
    }
}

fun voicePrompt(v: VoiceState, wakeEnabled: Boolean, error: String?): String = when (v) {
    VoiceState.OFF -> if (wakeEnabled) "Di: \"Jarvis\"" else "Toca el micrófono para hablar"
    VoiceState.WAKE_WORD -> "Di: \"Jarvis\""
    VoiceState.CONNECTING -> "Conectando voz…"
    VoiceState.LISTENING -> "Te escucho, jefe…"
    VoiceState.USER_SPEAKING -> "Escuchando…"
    VoiceState.THINKING -> "Procesando…"
    VoiceState.EXECUTING -> "Ejecutando…"
    VoiceState.SPEAKING -> "Hablando · toca ■ para interrumpir"
    VoiceState.ERROR -> error ?: "Error de voz"
}

@Composable
fun VoiceBar(
    voice: VoiceState,
    prompt: String,
    level: Float,
    onMic: () -> Unit,
    onInterrupt: () -> Unit,
    onKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hud = LocalHud.current
    val active = voice != VoiceState.OFF && voice != VoiceState.WAKE_WORD
    Row(
        modifier
            .hudFrame(cut = 18.dp, corners = setOf(Corner.TopLeft, Corner.BottomRight, Corner.TopRight, Corner.BottomLeft), accent = if (active) hud.bright else hud.primary, line = hud.line, fill = hud.panel, glow = active)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(hud.primary.copy(alpha = if (active) 0.55f else 0.25f), Color.Transparent)))
                .clickable(role = Role.Button, onClick = onMic)
                .semantics { contentDescription = if (active) "Terminar conversación de voz" else "Hablar con JARVIS" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(hud.primary, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(1.5.dp.toPx())) }
            Icon(if (active) Icons.Outlined.Stop else Icons.Outlined.Mic, null, tint = hud.bright, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(prompt, style = HudText.body.copy(fontSize = 18.sp), color = hud.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (voice == VoiceState.SPEAKING) {
            Icon(Icons.Outlined.Stop, "Interrumpir", tint = hud.bright, modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onInterrupt).padding(6.dp))
        }
        Waveform(level, active, Modifier.width(56.dp).height(30.dp))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.Keyboard, "Escribir", tint = hud.primary, modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onKeyboard).padding(6.dp))
    }
}

/** Barras de onda dirigidas por el nivel real de audio (planas si no hay audio). */
@Composable
fun Waveform(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(active) { while (active) { phase += 0.35f; delay(50) } }
    Canvas(modifier) {
        val bars = 7
        val gap = size.width / (bars * 2f)
        for (i in 0 until bars) {
            val k = if (active) (0.25f + level * 0.75f) * (0.55f + 0.45f * sin(phase + i * 0.9f)) else 0.12f
            val h = size.height * k.coerceIn(0.08f, 1f)
            val x = gap + i * gap * 2
            drawLine(hud.bright, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), strokeWidth = gap * 0.8f, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun QuotePanel(next: TaskItem?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier, corners = setOf(Corner.TopRight, Corner.BottomLeft), onClick = onClick) {
        if (next != null) {
            Text("PRÓXIMA TAREA", style = HudText.label, color = hud.bright)
            Spacer(Modifier.height(4.dp))
            Text(next.title, style = HudText.strong, color = hud.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            next.due?.let { Text(SpanishDates.dueLabel(it, next.hasTime, LocalDate.now()), style = HudText.bodySmall, color = if (next.isOverdue(LocalDateTime.now())) hud.error else hud.textDim) }
        } else {
            Text("GRANDES COSAS\nCOMIENZAN CON\nUNA BUENA IDEA", style = HudText.label.copy(fontSize = 11.sp, lineHeight = 16.sp), color = hud.bright, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Accesos a los módulos de JARVIS (chat, web, negocios, mapa, tareas, memoria, habilidades, ajustes). */
@Composable
fun ModuleDock(onNavigate: (String) -> Unit, modifier: Modifier = Modifier, compactLabels: Boolean = false) {
    val modules = listOf(
        Triple("chat", "Chat", Icons.Outlined.Psychology),
        Triple("web", "Web", Icons.Outlined.Language),
        Triple("business", "Negocios", Icons.Outlined.Storefront),
        Triple("map", "Mapa", Icons.Outlined.Map),
        Triple("tasks", "Tareas", Icons.Outlined.TaskAlt),
        Triple("memory", "Memoria", Icons.Outlined.Memory),
        Triple("skills", "Skills", Icons.Outlined.Extension),
        Triple("settings", "Ajustes", Icons.Outlined.Settings),
    )
    if (compactLabels) {
        // Teléfono en vertical: ancho fijo y desplazamiento lateral para que ningún botón quede aplastado.
        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modules.forEach { (route, label, icon) ->
                HudButton(label.take(8), icon, onClick = { onNavigate(route) }, modifier = Modifier.width(68.dp).fillMaxHeight())
            }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modules.forEach { (route, label, icon) ->
                HudButton(label, icon, onClick = { onNavigate(route) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Actividad real junto al núcleo, como el recuadro "ANALIZANDO / CONECTANDO…" del diseño. */
@Composable
fun StatusTicker(lines: List<String>, error: Boolean, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    Row(modifier) {
        Canvas(Modifier.width(10.dp).height((lines.size * 16 + 8).dp)) {
            drawLine(hud.primary, Offset(size.width, 0f), Offset(0f, 0f), strokeWidth = 1.dp.toPx())
            drawLine(hud.primary, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
            drawLine(hud.primary, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Column(Modifier.padding(start = 6.dp, top = 4.dp)) {
            lines.forEachIndexed { i, l ->
                Text(l, style = HudText.label, color = (if (error) hud.error else hud.bright).copy(alpha = 0.45f + 0.55f * (i + 1) / lines.size), maxLines = 1)
            }
        }
    }
}

@Composable
fun SmallIconStatus(ok: Boolean, label: String) {
    val hud = LocalHud.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (ok) hud.ok else hud.warn, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = HudText.bodySmall, color = hud.textDim)
    }
}

