package com.example.jarvis.core.tools

import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.json.toCompactString
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.UiEventSink
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Llamada a herramienta propuesta por el modelo (o por el detector local de intenciones). */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** Datos de sesión compartidos por las herramientas: últimos resultados, selección, origen. */
class ToolContext(
    val source: RequestSource,
    val ui: UiEventSink,
    val working: WorkingContext,
    /** Habilidad que hace la llamada (null = la pide el modelo, un botón o el detector local). */
    val viaSkill: String? = null,
) {
    fun forSkill(skillId: String) = ToolContext(source, ui, working, viaSkill = skillId)
}

/** Resultado estructurado de una herramienta. `message` es un resumen en español apto para el usuario. */
data class ToolResult(
    val ok: Boolean,
    val message: String,
    val data: JsonElement = JsonNull,
    val cards: List<ResultCard> = emptyList(),
    val errorCode: String? = null,
) {
    /** Representación compacta que se devuelve al modelo (function_call_output). */
    fun toModelOutput(maxChars: Int = 6000): String {
        val obj = buildJsonObject {
            put("ok", ok)
            put("message", message)
            if (errorCode != null) put("error", errorCode)
            if (data !is JsonNull) put("data", data)
        }
        return obj.toCompactString(maxChars)
    }

    companion object {
        fun ok(message: String, data: JsonElement = JsonNull, cards: List<ResultCard> = emptyList()) =
            ToolResult(true, message, data, cards)

        fun error(code: String, message: String) = ToolResult(false, message, errorCode = code)
    }
}

fun interface ToolHandler {
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * Registro de herramientas: une cada definición del contrato con su implementación.
 * `verifyComplete` garantiza que no haya herramientas declaradas sin implementación ni al revés.
 */
class ToolRegistry(definitions: List<ToolDefinition>) {
    private val defs = definitions.associateBy { it.name }
    private val handlers = mutableMapOf<String, ToolHandler>()

    fun register(name: String, handler: ToolHandler) {
        require(name in defs) { "La herramienta $name no está en el contrato tools.json" }
        handlers[name] = handler
    }

    fun definition(name: String): ToolDefinition? = defs[name]
    fun handler(name: String): ToolHandler? = handlers[name]

    /** Herramientas que se ofrecen al modelo: sin las internas y, en texto, sin las solo-voz. */
    fun definitionsFor(voice: Boolean): List<ToolDefinition> =
        defs.values.filter { it.name in handlers && it.exposed && (voice || !it.voiceOnly) }

    fun all(): List<ToolDefinition> = defs.values.toList()

    fun missingHandlers(): List<String> = defs.keys.filter { it !in handlers }

    fun verifyComplete() {
        val missing = missingHandlers()
        check(missing.isEmpty()) { "Herramientas sin implementación: $missing" }
    }

    companion object {
        fun parseArguments(json: String): JsonObject? = runCatching {
            if (json.isBlank()) JsonObject(emptyMap()) else JarvisJson.parseToJsonElement(json) as? JsonObject
        }.getOrNull()

        fun argsOf(vararg pairs: Pair<String, Any>): String = buildJsonObject {
            for ((k, v) in pairs) when (v) {
                is String -> put(k, v)
                is Number -> put(k, JsonPrimitive(v))
                is Boolean -> put(k, v)
                is JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }.toString()
    }
}
