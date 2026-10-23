package com.example.jarvis.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.jarvis.core.model.CoreState
import com.example.jarvis.core.realtime.VoiceState
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.core.HoloGlobe
import com.example.jarvis.ui.theme.LocalHud

/**
 * Pantalla principal según el diseño de referencia: núcleo holográfico protagonista, paneles HUD
 * y barra de voz. El mapa NO se abre aquí: solo cuando el usuario lo pide.
 */
@Composable
fun HomeScreen(vm: HomeViewModel, onNavigate: (String) -> Unit, onLaunchError: (String) -> Unit) {
    val hud = LocalHud.current
    val core by vm.coreState.collectAsState()
    val voice by vm.voiceState.collectAsState()
    val mic by vm.micLevel.collectAsState()
    val out by vm.outputLevel.collectAsState()
    val settings by vm.settings.collectAsState()
    val ticker by vm.ticker.collectAsState()
    val weather by vm.weather.collectAsState()
    val status by vm.status.collectAsState()
    val apps by vm.apps.collectAsState()
    val recent by vm.recentTools.collectAsState()
    val next by vm.nextTask.collectAsState()

    val prompt = voicePrompt(voice, settings.wakeWordEnabled, null)
    val level = when (voice) {
        VoiceState.SPEAKING -> out
        VoiceState.LISTENING, VoiceState.USER_SPEAKING -> mic
        else -> 0f
    }
    val assistantLabel = when (voice) {
        VoiceState.OFF -> if (status.system) "Sistemas en línea" else status.backendLabel
        VoiceState.WAKE_WORD -> "Esperando la palabra clave"
        VoiceState.SPEAKING -> "Nivel de voz de JARVIS"
        VoiceState.LISTENING, VoiceState.USER_SPEAKING -> "Nivel de tu voz"
        else -> "Conversación activa"
    }
    val assistantBar = when (voice) {
        VoiceState.SPEAKING, VoiceState.LISTENING, VoiceState.USER_SPEAKING -> level
        VoiceState.OFF, VoiceState.WAKE_WORD -> status.score
        else -> 1f
    }
    val highlight = settings.weatherPlace?.let { com.example.jarvis.core.business.LatLon(it.lat, it.lon) }
    val launch: (AppTile) -> Unit = { tile -> vm.launch(tile) { err -> if (err != null) onLaunchError(err) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollWhileVisible() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(hud.background).safeDrawingPadding().testTag("home")) {
        val wide = maxWidth >= 720.dp && maxWidth > maxHeight
        if (wide) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Fila superior: marca, reloj, clima
                Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandPanel(Modifier.weight(0.30f).fillMaxHeight())
                    Spacer(Modifier.weight(0.06f))
                    ClockPanel(Modifier.weight(0.26f).fillMaxHeight())
                    Spacer(Modifier.weight(0.06f))
                    WeatherPanel(weather, onClick = { if (settings.weatherPlace == null) onNavigate("settings") else vm.refreshWeather() }, modifier = Modifier.weight(0.30f).fillMaxHeight())
                }
                // Zona central: apps | núcleo | actividades + asistente
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppsPanel(apps, launch, onEdit = { onNavigate("settings") }, modifier = Modifier.weight(0.22f).fillMaxHeight())
                    Box(Modifier.weight(0.52f).fillMaxHeight()) {
                        HoloGlobe(core, mic, out, hud, settings.reduceMotion, Modifier.fillMaxSize().testTag("core"), highlight)
                        StatusTicker(ticker, core == CoreState.ERROR, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 4.dp))
                        ModuleDock(onNavigate, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp))
                    }
                    Column(Modifier.weight(0.24f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActivityPanel(recent, onOpenChat = { onNavigate("chat") }, onShortcut = onNavigate, modifier = Modifier.weight(1.15f).fillMaxWidth())
                        AssistantPanel(voice, assistantBar, assistantLabel, onClick = { onNavigate("chat") }, modifier = Modifier.weight(0.85f).fillMaxWidth())
                    }
                }
                // Fila inferior: estado del sistema | barra de voz | frase / próxima tarea
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SystemStatusPanel(status, onClick = { onNavigate("settings") }, modifier = Modifier.weight(0.22f).fillMaxHeight())
                    VoiceBar(voice, prompt, level, vm::toggleVoice, vm::interruptVoice, { onNavigate("chat") }, Modifier.weight(0.52f).fillMaxHeight())
                    QuotePanel(next, onClick = { onNavigate("tasks") }, modifier = Modifier.weight(0.24f).fillMaxHeight())
                }
            }
        } else {
            PortraitHome(
                core = core, voice = voice, mic = mic, out = out, reduceMotion = settings.reduceMotion, ticker = ticker,
                weather = weather, status = status, apps = apps, recent = recent, next = next, prompt = prompt, level = level,
                assistantBar = assistantBar, assistantLabel = assistantLabel, highlight = highlight, weatherConfigured = settings.weatherPlace != null,
                vm = vm, launch = launch, onNavigate = onNavigate,
            )
        }
    }
}

@Composable
private fun PortraitHome(
    core: CoreState, voice: VoiceState, mic: Float, out: Float, reduceMotion: Boolean, ticker: List<String>,
    weather: WeatherUi, status: SystemStatus, apps: List<AppTile>, recent: List<com.example.jarvis.data.db.ToolLogEntity>,
    next: com.example.jarvis.core.tasks.TaskItem?, prompt: String, level: Float, assistantBar: Float, assistantLabel: String,
    highlight: com.example.jarvis.core.business.LatLon?, weatherConfigured: Boolean,
    vm: HomeViewModel, launch: (AppTile) -> Unit, onNavigate: (String) -> Unit,
) {
    val hud = LocalHud.current
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandPanel(Modifier.weight(0.36f).fillMaxHeight(), compact = true)
            ClockPanel(Modifier.weight(0.32f).fillMaxHeight(), compact = true)
            WeatherPanel(weather, onClick = { if (!weatherConfigured) onNavigate("settings") else vm.refreshWeather() }, modifier = Modifier.weight(0.32f).fillMaxHeight(), compact = true)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            HoloGlobe(core, mic, out, hud, reduceMotion, Modifier.fillMaxSize().testTag("core"), highlight)
            StatusTicker(ticker, core == CoreState.ERROR, Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
        }
        VoiceBar(voice, prompt, level, vm::toggleVoice, vm::interruptVoice, { onNavigate("chat") }, Modifier.fillMaxWidth().height(72.dp))
        ModuleDock(onNavigate, Modifier.fillMaxWidth().height(64.dp), compactLabels = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Apps", "Actividad", "Sistema", "JARVIS").forEachIndexed { i, label -> HudChip(label, onClick = { tab = i }, selected = tab == i) }
        }
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            when (tab) {
                0 -> AppsPanel(apps, launch, onEdit = { onNavigate("settings") }, modifier = Modifier.fillMaxSize(), columns = 5)
                1 -> ActivityPanel(recent, onOpenChat = { onNavigate("chat") }, onShortcut = onNavigate, modifier = Modifier.fillMaxSize())
                2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SystemStatusPanel(status, onClick = { onNavigate("settings") }, modifier = Modifier.weight(1.2f).fillMaxHeight())
                    QuotePanel(next, onClick = { onNavigate("tasks") }, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                else -> AssistantPanel(voice, assistantBar, assistantLabel, onClick = { onNavigate("chat") }, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}
