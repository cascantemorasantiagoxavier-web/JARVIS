package com.example.jarvis.core.orchestrator

import com.example.jarvis.core.brain.BrainProgress
import com.example.jarvis.core.brain.ConversationStore
import com.example.jarvis.core.brain.JarvisBrain
import com.example.jarvis.core.brain.ToolLabels
import com.example.jarvis.core.model.ChatEntry
import com.example.jarvis.core.model.CoreState
import com.example.jarvis.core.model.JarvisRequest
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.Role
import com.example.jarvis.core.model.ToolRun
import com.example.jarvis.core.model.UiEventSink
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.WorkingContext
import com.example.jarvis.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Actividad real visible junto al núcleo ("BUSCANDO NEGOCIOS…"). */
data class Activity(val state: CoreState, val label: String)

/**
 * Orquestador: punto de entrada único para chat, botones y transcripciones de voz.
 * Mantiene el historial visible, el estado del cerebro y la cancelación de operaciones.
 */
class JarvisOrchestrator(
    private val brain: JarvisBrain,
    private val store: ConversationStore,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    val working: WorkingContext,
    private val ui: UiEventSink,
) {
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    private val _activity = MutableStateFlow(Activity(CoreState.IDLE, "EN ESPERA"))
    val activity: StateFlow<Activity> = _activity.asStateFlow()

    private var current: Job? = null
    private val historyLock = Mutex()
    private var tempIds = -1L

    suspend fun loadHistory(limit: Int = 60) {
        val recent = store.recent(limit)
        _entries.value = recent
    }

    val isBusy: Boolean get() = current?.isActive == true

    /** Envía una petición. Si había otra en curso, se cancela (el usuario cambió de idea). */
    fun submit(text: String, source: RequestSource = RequestSource.TEXT): Job? = submitRequest(JarvisRequest(text, source))

    /** Igual que [submit] pero permite forzar la estrategia (FAST/BALANCED/DEEP). */
    fun submitRequest(request: JarvisRequest): Job? {
        val clean = request.text.trim()
        if (clean.isEmpty()) return null
        val source = request.source
        current?.cancel(CancellationException("Reemplazada por una nueva petición"))
        val job = scope.launch {
            append(ChatEntry(0, Role.USER, clean, time.now(), source))
            val pending = append(ChatEntry(0, Role.ASSISTANT, "", time.now(), source, pending = true))
            try {
                setActivity(CoreState.THINKING, "PENSANDO")
                val response = brain.handle(request.copy(text = clean), ToolContext(source, ui, working)) { p -> onProgress(p) }
                replace(
                    pending.copy(
                        text = response.text,
                        cards = response.cards,
                        toolRuns = response.toolRuns,
                        mode = response.mode,
                        usedModel = response.usedModel,
                        isError = response.isError,
                        pending = false,
                        at = time.now(),
                    ),
                )
                if (response.isError) flashError("ERROR") else setActivity(CoreState.IDLE, "EN ESPERA")
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    replace(pending.copy(text = "Operación cancelada.", pending = false, isError = true))
                }
                setActivity(CoreState.IDLE, "CANCELADO")
                throw e
            } catch (e: Exception) {
                replace(pending.copy(text = "Ocurrió un error inesperado: ${e.message ?: e::class.simpleName}", pending = false, isError = true))
                flashError("ERROR")
            }
        }
        current = job
        return job
    }

    fun cancel() {
        current?.cancel(CancellationException("Cancelada por el usuario"))
    }

    /** Registra una intervención de la sesión de voz en el mismo historial que el chat. */
    fun recordVoice(role: Role, text: String, cards: List<ResultCard> = emptyList(), runs: List<ToolRun> = emptyList()) {
        if (text.isBlank() && cards.isEmpty()) return
        scope.launch { append(ChatEntry(0, role, text, time.now(), RequestSource.VOICE, cards = cards, toolRuns = runs, usedModel = role == Role.ASSISTANT)) }
    }

    fun recordSystem(text: String) {
        scope.launch { append(ChatEntry(0, Role.SYSTEM, text, time.now(), RequestSource.AUTOMATION)) }
    }

    suspend fun clearHistory() {
        store.clear()
        _entries.value = emptyList()
    }

    private fun onProgress(p: BrainProgress) {
        when (p) {
            BrainProgress.Thinking -> setActivity(CoreState.THINKING, "PENSANDO")
            is BrainProgress.ExecutingTool -> setActivity(CoreState.EXECUTING, ToolLabels.activity(p.tool))
            is BrainProgress.ToolFinished -> setActivity(CoreState.THINKING, if (p.ok) "ANALIZANDO" else "REVISANDO ERROR")
        }
    }

    private fun setActivity(state: CoreState, label: String) {
        _activity.value = Activity(state, label)
    }

    private fun flashError(label: String) {
        _activity.value = Activity(CoreState.ERROR, label)
        scope.launch {
            kotlinx.coroutines.delay(3500)
            if (_activity.value.state == CoreState.ERROR) _activity.value = Activity(CoreState.IDLE, "EN ESPERA")
        }
    }

    private suspend fun append(entry: ChatEntry): ChatEntry = historyLock.withLock {
        val id = runCatching { store.append(entry) }.getOrElse { tempIds-- }
        val saved = entry.copy(id = id)
        _entries.update { (it + saved).takeLast(200) }
        saved
    }

    private suspend fun replace(entry: ChatEntry) = historyLock.withLock {
        if (entry.id > 0) runCatching { store.update(entry) }
        _entries.update { list -> list.map { if (it.id == entry.id) entry else it } }
    }
}
