package com.example.jarvis.ui.hud

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.jarvis.ai.realtime.RealtimeConnectionState
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.features.voice.VoiceViewModel
import com.example.jarvis.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReferenceTopHeader(state: AssistantState) {
    var timeStr by remember { mutableStateOf("00:00") }
    var dateStr by remember { mutableStateOf("Cargando...") }

    LaunchedEffect(Unit) {
        val localeEs = Locale.Builder().setLanguage("es").setRegion("ES").build()
        while (true) {
            val now = Date()
            timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            dateStr = SimpleDateFormat("EEE, d MMM yyyy", localeEs).format(now).uppercase()
            delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(HudSurfaceDark, RoundedCornerShape(4.dp))
                .border(1.dp, HudGoldAccent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "⚡", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "J.A.R.V.I.S.", style = MaterialTheme.typography.titleMedium, color = HudGoldAccent)
                }
                Text(text = "Siempre a tu lado, jefe.", style = MaterialTheme.typography.labelSmall, color = HudTextCyan)
            }
        }

        Box(
            modifier = Modifier
                .background(HudSurfaceDark, RoundedCornerShape(16.dp))
                .border(1.5.dp, HudGoldAccent, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = timeStr, style = MaterialTheme.typography.headlineMedium, color = HudGoldAccent)
                Text(text = dateStr, style = MaterialTheme.typography.labelSmall, color = HudTextMuted)
            }
        }
    }
}

@Composable
fun ReferenceAppGrid(onAppAction: (String) -> Unit) {
    val apps = listOf(
        Triple("Spotify", "com.spotify.music", "🎵"),
        Triple("YouTube", "com.google.android.youtube", "▶️"),
        Triple("Maps", "com.google.android.apps.maps", "🗺️"),
        Triple("Chrome", "com.android.chrome", "🌐"),
        Triple("Gmail", "com.google.android.gm", "✉️"),
        Triple("Play Store", "com.android.vending", "▶"),
        Triple("WhatsApp", "com.whatsapp", "💬"),
        Triple("Instagram", "com.instagram.android", "📷"),
        Triple("TikTok", "com.zhiliaoapp.musically", "🎬"),
        Triple("X", "com.twitter.android", "𝕏"),
        Triple("Calc", "com.google.android.calculator", "🔢"),
        Triple("Calendar", "com.google.android.calendar", "📅"),
        Triple("Settings", "com.android.settings", "⚙️"),
        Triple("Camera", "com.android.camera", "📸"),
        Triple("Photos", "com.google.android.apps.photos", "🖼️")
    )

    Box(
        modifier = Modifier
            .background(HudSurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, HudGoldAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text = "APLICACIONES HUD", style = MaterialTheme.typography.labelMedium, color = HudGoldAccent)
            Spacer(modifier = Modifier.height(1.dp))
            apps.chunked(3).forEach { rowApps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    rowApps.forEach { (name, pkg, icon) ->
                        val appMgr = AppContainer.appManager
                        val isInstalled = appMgr?.isAppInstalled(pkg) == true

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .background(if (isInstalled) HudSurface else HudSurface.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, if (isInstalled) HudGoldAccent.copy(alpha = 0.4f) else HudErrorRed.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .clickable {
                                    if (appMgr != null && appMgr.isAppInstalled(pkg)) {
                                        appMgr.launchApp(pkg)
                                        onAppAction("Abriendo $name")
                                    } else {
                                        onAppAction("$name: NO DISPONIBLE EN ESTE DISPOSITIVO")
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                Text(text = icon, style = MaterialTheme.typography.labelSmall)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isInstalled) name else "$name (N/D)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isInstalled) Color.White else HudTextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReferenceRightPanels() {
    val appMgr = AppContainer.appManager
    val batteryRes = appMgr?.getBatteryLevel()
    val batteryPct = batteryRes?.getOrNull() ?: 100
    val model = appMgr?.getDeviceModel() ?: "Android Device"
    val androidVer = appMgr?.getAndroidVersion() ?: "Android System"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .background(HudSurfaceDark, RoundedCornerShape(6.dp))
                .border(1.dp, HudGoldAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "TELEMETRÍA REAL DEL DISPOSITIVO", style = MaterialTheme.typography.labelMedium, color = HudGoldAccent)
                ActivityRow("Modelo", model, "📱")
                ActivityRow("Sistema", androidVer, "⚡")
                ActivityRow("Batería", "$batteryPct%", "🔋")
                ActivityRow("Cerebro IA", "Gemini (gemini-3.8-flash)", "🧠")
            }
        }

        Box(
            modifier = Modifier
                .background(HudSurfaceDark, RoundedCornerShape(6.dp))
                .border(1.dp, HudCyanNeon.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "🛡️", style = MaterialTheme.typography.headlineMedium)
                Column {
                    Text(text = "ESTADO DE ENERGÍA Y RED", style = MaterialTheme.typography.titleSmall, color = HudCyanNeon)
                    Text(text = "Batería: $batteryPct% | Sistema Operativo", style = MaterialTheme.typography.labelSmall, color = HudTextMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (batteryPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (batteryPct > 20) HudSuccessGreen else HudErrorRed,
                        trackColor = HudSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ActivityRow(title: String, subtitle: String, icon: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = HudGoldAccent)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = HudTextMuted)
        }
    }
}

@Composable
fun RadarTelemetrySection(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(HudSurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, HudGoldAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(modifier = Modifier.size(36.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = HudGoldAccent.copy(alpha = 0.5f), radius = size.width / 2f, center = center, style = Stroke(1.dp.toPx()))
                drawCircle(color = HudGoldAccent.copy(alpha = 0.8f), radius = size.width / 4f, center = center, style = Stroke(1.dp.toPx()))
                drawLine(color = HudGoldAccent.copy(alpha = 0.4f), start = Offset(0f, center.y), end = Offset(size.width, center.y))
                drawLine(color = HudGoldAccent.copy(alpha = 0.4f), start = Offset(center.x, 0f), end = Offset(center.x, size.height))
            }
            Column {
                Text(text = "RADAR | TELEMETRY", style = MaterialTheme.typography.labelSmall, color = HudGoldAccent)
                Text(text = "SISTEMA SEGURO", style = MaterialTheme.typography.labelSmall, color = HudSuccessGreen)
            }
        }
    }
}

@Composable
fun QuoteBoxSection(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(HudSurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, HudGoldAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\"A veces hay que correr antes de caminar, jefe.\"",
            style = MaterialTheme.typography.labelSmall,
            color = HudGoldAccent
        )
    }
}

@Composable
fun ReferenceBottomBar(
    voiceViewModel: VoiceViewModel,
    onMessage: (String) -> Unit
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceViewModel.toggleListening()
        } else {
            onMessage("Permiso de micrófono denegado")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurfaceDark, RoundedCornerShape(32.dp))
            .border(1.5.dp, HudGoldAccent, RoundedCornerShape(32.dp))
            .clickable {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    voiceViewModel.toggleListening()
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🎙️", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (voiceState.voiceState == RealtimeConnectionState.LISTENING) "Escuchando... Habla tu consulta" else "Toca para hablar con JARVIS",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HudGoldAccent
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(4) { idx ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((12 + (idx * 4)).dp)
                            .background(HudGoldAccent, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}
