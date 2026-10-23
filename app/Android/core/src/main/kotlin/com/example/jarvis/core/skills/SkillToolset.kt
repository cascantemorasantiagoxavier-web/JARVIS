package com.example.jarvis.core.skills

import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.tools.ToolHandler
import com.example.jarvis.core.tools.ToolRegistry
import com.example.jarvis.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Meta-herramientas que conectan las habilidades con el modelo (chat y GPT Realtime):
 * list_skills (descubrir), describe_skill (parámetros bajo demanda) y run_skill (ejecutar).
 * El catálogo breve va en las instrucciones; los esquemas completos solo cuando se piden.
 */
class SkillToolset(private val registry: SkillRegistry, private val engine: SkillEngine, private val env: SkillEnvironment) {

    fun registerAll(tools: ToolRegistry) {
        tools.register("list_skills", ToolHandler { a, _ -> list(a.str("query")) })
        tools.register("describe_skill", ToolHandler { a, _ -> describe(a.str("skill")!!) })
        tools.register("run_skill", ToolHandler { a, ctx ->
            val id = a.str("skill")!!.trim()
            val result = engine.run(id, a.obj("input") ?: JsonObject(emptyMap()), ctx)
            toToolResult(result)
        })
    }

    private fun list(query: String?): ToolResult {
        val ids = if (query.isNullOrBlank()) registry.ids() else registry.select(query, 12).map { it.id }
        val rows = ids.map { id ->
            val m = registry.manifest(id)!!
            val a = registry.availability(id, env)
            mapOf("id" to id, "name" to m.name, "state" to a.state.label, "reasons" to a.reasons, "summary" to m.shortDescription)
        }
        val byState = rows.groupingBy { it["state"] as String }.eachCount()
        val lines = rows.map { "${it["name"]} — ${it["state"]}" + ((it["reasons"] as List<*>).takeIf { r -> r.isNotEmpty() }?.let { r -> " (${r.joinToString("; ")})" } ?: "") }
        return ToolResult.ok(
            "Tengo ${rows.size} habilidades: " + byState.entries.joinToString(", ") { "${it.value} ${it.key.lowercase()}" } + ".",
            jsonOf("skills" to rows),
            listOf(ResultCard.Info("Habilidades de JARVIS", lines)),
        )
    }

    private fun describe(id: String): ToolResult {
        val d = registry.describe(id.trim(), env) ?: return ToolResult.error("unknown_skill", "No existe la habilidad \"$id\". ¿Quizás: ${registry.suggest(id).joinToString()}?")
        return ToolResult.ok("Parámetros de la habilidad $id.", d)
    }

    companion object {
        /** El resultado completo (estado, datos, fuentes, avisos) viaja al modelo para que lo explique. */
        fun toToolResult(r: SkillResult): ToolResult {
            return ToolResult(ok = r.ok, message = r.summary, data = r.toJson(), cards = r.cards, errorCode = if (r.ok) null else (r.errorCode ?: r.status.wire))
        }
    }
}
