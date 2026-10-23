package com.example.jarvis.core.brain

import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ChatEntry
import com.example.jarvis.core.net.BackendClient
import com.example.jarvis.core.net.ChatRequest
import com.example.jarvis.core.net.ChatToolSpec
import com.example.jarvis.core.tools.ToolCall
import com.example.jarvis.core.tools.ToolDefinition
import kotlinx.serialization.json.JsonObject

data class LlmRequest(
    val instructions: String,
    val input: List<JsonObject>,
    val tools: List<ToolDefinition>,
    val mode: AssistantMode,
)

data class LlmResult(
    val text: String,
    val toolCalls: List<ToolCall>,
    /** Items opacos (mensajes, llamadas, razonamiento) que deben reenviarse en la siguiente ronda. */
    val outputItems: List<JsonObject>,
    val incompleteReason: String? = null,
)

/** Modelo de lenguaje remoto. La implementación real pasa por el backend (BackendLlmClient). */
interface LlmClient {
    suspend fun respond(request: LlmRequest): LlmResult
}

class BackendLlmClient(private val backend: BackendClient) : LlmClient {
    override suspend fun respond(request: LlmRequest): LlmResult {
        val res = backend.chat(
            ChatRequest(
                instructions = request.instructions,
                input = request.input,
                tools = request.tools.map { ChatToolSpec(it.name, it.description, it.parameters) },
                mode = request.mode.name,
            ),
        )
        return LlmResult(
            text = res.text,
            toolCalls = res.toolCalls.map { ToolCall(it.callId, it.name, it.arguments) },
            outputItems = res.outputItems,
            incompleteReason = res.incompleteReason,
        )
    }
}

/** Historial de conversación persistente (Room en Android). */
interface ConversationStore {
    suspend fun recent(limit: Int): List<ChatEntry>
    suspend fun append(entry: ChatEntry): Long
    suspend fun update(entry: ChatEntry)
    suspend fun clear()
}

/** Progreso real del cerebro; el orquestador lo traduce a estados del núcleo. */
sealed interface BrainProgress {
    data object Thinking : BrainProgress
    data class ExecutingTool(val tool: String) : BrainProgress
    data class ToolFinished(val tool: String, val ok: Boolean) : BrainProgress
}

/** Etiquetas en español para la actividad visible ("BUSCANDO NEGOCIOS…"). */
object ToolLabels {
    fun activity(tool: String): String = when (tool) {
        "business_search", "business_details" -> "BUSCANDO NEGOCIOS"
        "business_verify" -> "VERIFICANDO FUENTES"
        "web_search" -> "BUSCANDO EN LA WEB"
        "fetch_page" -> "LEYENDO FUENTE"
        "remember", "update_memory" -> "GUARDANDO EN MEMORIA"
        "recall" -> "CONSULTANDO MEMORIA"
        "forget" -> "BORRANDO RECUERDO"
        "search_notes" -> "CONSULTANDO OBSIDIAN"
        "create_task", "complete_task", "delete_task", "list_tasks", "plan_day" -> "GESTIONANDO TAREAS"
        "calendar_events", "create_calendar_event" -> "CONSULTANDO CALENDARIO"
        "get_weather" -> "CONSULTANDO CLIMA"
        "calculate", "convert_units" -> "CALCULANDO"
        "show_on_map", "open_location", "get_directions" -> "PREPARANDO MAPA"
        "save_prospect", "list_prospects", "update_prospect", "organize_prospects" -> "GESTIONANDO PROSPECTOS"
        "open_app" -> "ABRIENDO APLICACIÓN"
        "call_phone" -> "PREPARANDO LLAMADA"
        "get_device_status" -> "DIAGNÓSTICO DEL SISTEMA"
        else -> "EJECUTANDO ${tool.uppercase()}"
    }
}
