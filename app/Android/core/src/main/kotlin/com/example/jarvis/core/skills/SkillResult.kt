package com.example.jarvis.core.skills

import com.example.jarvis.core.json.toCompactString
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.ToolRun
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Resultado de una ejecución. Solo COMPLETED y PARTIAL llevan datos validados contra outputSchema. */
enum class SkillStatus(val wire: String) {
    COMPLETED("completed"),
    PARTIAL("partial"),
    NEEDS_CONFIGURATION("needs_configuration"),
    PERMISSION_DENIED("permission_denied"),
    INVALID_INPUT("invalid_input"),
    NOT_CONFIRMED("not_confirmed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    NOT_IMPLEMENTED("not_implemented"),
    NOT_FOUND("not_found"),
    ;

    val hasData: Boolean get() = this == COMPLETED || this == PARTIAL
}

/** Fuente verificable de un dato (página, nota, base de datos, registro local). */
data class SkillSource(
    val title: String,
    val url: String? = null,
    val kind: String = "web",
    val retrievedAt: String? = null,
)

/** Lo que devuelve la implementación; el motor lo completa y lo valida. */
data class SkillOutcome(
    val status: SkillStatus,
    val summary: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val sources: List<SkillSource> = emptyList(),
    val warnings: List<String> = emptyList(),
    val cards: List<ResultCard> = emptyList(),
    val errorCode: String? = null,
    val recovery: String? = null,
)

data class SkillResult(
    val skillId: String,
    val status: SkillStatus,
    val summary: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val sources: List<SkillSource> = emptyList(),
    val warnings: List<String> = emptyList(),
    val cards: List<ResultCard> = emptyList(),
    val toolRuns: List<ToolRun> = emptyList(),
    val errorCode: String? = null,
    val recovery: String? = null,
    val durationMs: Long = 0,
) {
    val ok: Boolean get() = status.hasData

    /** Salida compacta para el modelo (function_call_output). Las fuentes van con su URL para citarlas. */
    fun toModelOutput(maxChars: Int = 7000): String = toJson().toCompactString(maxChars)

    /** Sobre completo: estado, resumen, datos validados, fuentes, avisos y recuperación. */
    fun toJson(): JsonObject = buildJsonObject {
        put("skill", skillId)
        put("status", status.wire)
        put("summary", summary)
        if (status.hasData) put("data", data)
        if (sources.isNotEmpty()) put(
            "sources",
            buildJsonArray {
                sources.take(12).forEach { s ->
                    add(buildJsonObject {
                        put("title", s.title)
                        s.url?.let { put("url", it) }
                        put("kind", s.kind)
                        s.retrievedAt?.let { put("retrieved_at", it) }
                    })
                }
            },
        )
        if (warnings.isNotEmpty()) put("warnings", buildJsonArray { warnings.take(8).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        errorCode?.let { put("error", it) }
        recovery?.let { put("recovery", it) }
    }
}
