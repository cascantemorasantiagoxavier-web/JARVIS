package com.example.jarvis.ui.home

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jarvis.AppContainer
import com.example.jarvis.core.device.ActionResult
import com.example.jarvis.core.device.SpecialApp
import com.example.jarvis.core.model.CoreState
import com.example.jarvis.core.orchestrator.CoreStateArbiter
import com.example.jarvis.core.realtime.VoiceState
import com.example.jarvis.core.tasks.TaskFilter
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.weather.WeatherException
import com.example.jarvis.core.weather.WeatherNow
import com.example.jarvis.data.db.ToolLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WeatherUi {
    data object NotConfigured : WeatherUi
    data object Loading : WeatherUi
    data class Data(val weather: WeatherNow) : WeatherUi
    data class Error(val message: String) : WeatherUi
}

/** Estado real de los subsistemas mostrado en el panel inferior izquierdo. */
data class SystemStatus(
    val time: Boolean = true,
    val location: Boolean = false,
    val apps: Boolean = false,
    val internet: Boolean = false,
    val system: Boolean = false,
    val battery: Int? = null,
    val backendLabel: String = "Sin comprobar",
) {
    val score: Float get() = listOf(time, location, apps, internet, system).count { it } / 5f
}

data class AppTile(val label: String, val target: String, val icon: ImageBitmap?)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val coreState: StateFlow<CoreState> = combine(c.orchestrator.activity, c.voice.state) { a, v -> CoreStateArbiter.combine(a.state, v) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoreState.IDLE)

    val voiceState: StateFlow<VoiceState> = c.voice.state
    val micLevel = c.levels.mic
    val outputLevel = c.levels.output
    val settings = c.settings.settings
    val notices = c.voice.notices
    val pendingConfirmation = c.confirmations.pending

    /** Últimas actividades reales (registro de herramientas). */
    val recentTools: StateFlow<List<ToolLogEntity>> = c.db.toolLog().observeRecent(4)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ticker = MutableStateFlow(listOf("EN ESPERA"))
    /** Líneas de actividad real junto al núcleo ("BUSCANDO NEGOCIOS", "PENSANDO"…). */
    val ticker: StateFlow<List<String>> = _ticker.asStateFlow()

    private val _weather = MutableStateFlow<WeatherUi>(WeatherUi.NotConfigured)
    val weather: StateFlow<WeatherUi> = _weather.asStateFlow()

    private val _status = MutableStateFlow(SystemStatus())
    val status: StateFlow<SystemStatus> = _status.asStateFlow()

    private val _apps = MutableStateFlow<List<AppTile>>(emptyList())
    val apps: StateFlow<List<AppTile>> = _apps.asStateFlow()

    private val _nextTask = MutableStateFlow<TaskItem?>(null)
    val nextTask: StateFlow<TaskItem?> = _nextTask.asStateFlow()

    init {
        viewModelScope.launch {
            combine(c.orchestrator.activity, c.voice.state) { a, v -> voiceLabel(v) ?: a.label }
                .distinctUntilChanged()
                .collect { label ->
                    _ticker.value = when (label) {
                        "EN ESPERA", "ESPERANDO \"JARVIS\"" -> listOf(label, "LISTO PARA TI…")
                        else -> (_ticker.value.filter { it != "LISTO PARA TI…" && it != label } + label).takeLast(4)
                    }
                }
        }
        viewModelScope.launch { c.settings.settings.map { it.weatherPlace }.distinctUntilChanged().collect { refreshWeather() } }
        viewModelScope.launch { c.settings.settings.map { it.favoriteApps }.distinctUntilChanged().collect { loadApps(it) } }
    }

    private var weatherAt = 0L

    /**
     * Actualiza estado, próxima tarea y clima mientras la pantalla está visible. La pantalla lo
     * llama con repeatOnLifecycle(STARTED): en segundo plano no hay sondeo ni tráfico de red.
     */
    suspend fun pollWhileVisible() {
        while (true) {
            updateStatus()
            _nextTask.value = runCatching {
                (c.tasks.list(TaskFilter.OVERDUE) + c.tasks.list(TaskFilter.PENDING).filter { it.due != null }).firstOrNull()
            }.getOrNull()
            if (System.currentTimeMillis() - weatherAt > 30 * 60_000L) refreshWeather()
            delay(60_000)
        }
    }

    private fun voiceLabel(v: VoiceState): String? = when (v) {
        VoiceState.WAKE_WORD -> "ESPERANDO \"JARVIS\""
        VoiceState.CONNECTING -> "CONECTANDO VOZ"
        VoiceState.LISTENING -> "ESCUCHANDO"
        VoiceState.USER_SPEAKING -> "TE ESCUCHO"
        VoiceState.THINKING -> "PENSANDO"
        VoiceState.EXECUTING -> null
        VoiceState.SPEAKING -> "RESPONDIENDO"
        VoiceState.ERROR -> "ERROR DE VOZ"
        VoiceState.OFF -> null
    }

    fun refreshWeather() {
        val place = c.settings.current.weatherPlace ?: run { _weather.value = WeatherUi.NotConfigured; return }
        _weather.value = WeatherUi.Loading
        weatherAt = System.currentTimeMillis()
        viewModelScope.launch {
            _weather.value = try {
                WeatherUi.Data(c.weather.current(place))
            } catch (e: WeatherException) {
                WeatherUi.Error(e.message ?: "Clima no disponible")
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch { updateStatus() }
    }

    private suspend fun updateStatus() {
        run {
            val net = c.device.network()
            val battery = c.device.battery()?.percent
            val backend = if (!c.backendConfigured()) {
                false to "Servidor sin configurar"
            } else {
                runCatching { c.backend.health() }.fold(
                    { h -> true to "Servidor en línea" + if (!h.features.realtime) " (voz sin clave)" else "" },
                    { false to "Servidor sin respuesta" },
                )
            }
            _status.value = SystemStatus(
                time = true,
                location = c.settings.current.weatherPlace != null || c.settings.current.defaultLocation != null,
                apps = _apps.value.isNotEmpty(),
                internet = net.connected && net.validated,
                system = backend.first,
                battery = battery,
                backendLabel = backend.second,
            )
        }
    }

    private suspend fun loadApps(favorites: List<String>) {
        val pm = c.app.packageManager
        val tiles = withContext(Dispatchers.IO) {
            favorites.mapNotNull { target ->
                if (target.startsWith("special:")) {
                    val kind = runCatching { SpecialApp.valueOf(target.removePrefix("special:")) }.getOrNull() ?: return@mapNotNull null
                    AppTile(specialLabel(kind), target, null)
                } else {
                    val info = runCatching { pm.getApplicationInfo(target, 0) }.getOrNull() ?: return@mapNotNull null
                    if (pm.getLaunchIntentForPackage(target) == null) return@mapNotNull null
                    val icon: Drawable? = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                    AppTile(pm.getApplicationLabel(info).toString(), target, icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() })
                }
            }
        }
        _apps.value = tiles
        _status.value = _status.value.copy(apps = tiles.isNotEmpty())
    }

    fun launch(tile: AppTile, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val r = if (tile.target.startsWith("special:")) {
                c.device.openSpecial(SpecialApp.valueOf(tile.target.removePrefix("special:")))
            } else {
                c.device.openApp(tile.target)
            }
            onResult((r as? ActionResult.Failed)?.reason)
        }
    }

    fun toggleVoice() = c.voice.toggleConversation()
    fun interruptVoice() = c.voice.interrupt()
    fun answerConfirmation(ok: Boolean) = c.confirmations.answer(ok)

    companion object {
        fun specialLabel(k: SpecialApp) = when (k) {
            SpecialApp.CAMERA -> "Cámara"
            SpecialApp.SETTINGS -> "Configuración"
            SpecialApp.DIALER -> "Teléfono"
            SpecialApp.CALCULATOR -> "Calculadora"
            SpecialApp.CALENDAR -> "Calendario"
            SpecialApp.BROWSER -> "Navegador"
        }
    }
}
