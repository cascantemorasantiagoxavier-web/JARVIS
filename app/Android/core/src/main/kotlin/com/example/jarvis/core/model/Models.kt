package com.example.jarvis.core.model

import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.Prospect
import com.example.jarvis.core.memory.MemoryItem
import com.example.jarvis.core.net.PageContent
import com.example.jarvis.core.net.WebSearchResult
import com.example.jarvis.core.notes.NoteSnippet
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.weather.WeatherNow
import java.time.Instant

/** Estados reales del asistente; el núcleo visual los refleja. */
enum class CoreState { IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR }

/** Estrategia de ejecución: cambia rondas de herramientas, esfuerzo y uso de rutas locales. */
enum class AssistantMode { FAST, BALANCED, DEEP }

enum class RequestSource { TEXT, VOICE, BUTTON, AUTOMATION }

data class JarvisRequest(
    val text: String,
    val source: RequestSource = RequestSource.TEXT,
    val forcedMode: AssistantMode? = null,
)

/** Contenido estructurado que acompaña a una respuesta y la UI dibuja como tarjetas. */
sealed interface ResultCard {
    data class Businesses(val result: BusinessSearchResult) : ResultCard
    data class WebResults(val result: WebSearchResult) : ResultCard
    data class Page(val page: PageContent) : ResultCard
    data class Tasks(val title: String, val tasks: List<TaskItem>) : ResultCard
    data class Memories(val title: String, val items: List<MemoryItem>) : ResultCard
    data class Notes(val snippets: List<NoteSnippet>) : ResultCard
    data class Prospects(val title: String, val groups: Map<String, List<Prospect>>) : ResultCard
    data class Weather(val weather: WeatherNow) : ResultCard
    data class Info(val title: String, val lines: List<String>) : ResultCard
}

/** Navegación que solo la UI puede ejecutar (p. ej. abrir la pantalla del mapa). */
sealed interface UiEvent {
    data class ShowMap(val businessIds: List<String>) : UiEvent
    data object EndVoiceSession : UiEvent
}

fun interface UiEventSink {
    fun emit(event: UiEvent)
}

data class ToolRun(
    val name: String,
    val ok: Boolean,
    val message: String,
    val durationMs: Long,
    val errorCode: String? = null,
)

enum class Role { USER, ASSISTANT, SYSTEM }

/** Entrada visible del historial de conversación (chat y voz comparten el mismo historial). */
data class ChatEntry(
    val id: Long,
    val role: Role,
    val text: String,
    val at: Instant,
    val source: RequestSource = RequestSource.TEXT,
    val cards: List<ResultCard> = emptyList(),
    val toolRuns: List<ToolRun> = emptyList(),
    val mode: AssistantMode? = null,
    val usedModel: Boolean = false,
    val isError: Boolean = false,
    val pending: Boolean = false,
)

data class JarvisResponse(
    val text: String,
    val cards: List<ResultCard> = emptyList(),
    val toolRuns: List<ToolRun> = emptyList(),
    val mode: AssistantMode,
    val usedModel: Boolean,
    val isError: Boolean = false,
)
