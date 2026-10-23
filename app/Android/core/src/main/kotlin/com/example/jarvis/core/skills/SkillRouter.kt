package com.example.jarvis.core.skills

import com.example.jarvis.core.tools.ToolContext
import kotlinx.serialization.json.JsonObject

/**
 * Ruta local de habilidades (sin modelo): solo actúa si una habilidad marcada con localRoute sabe
 * convertir la frase en una entrada válida. Si varias pueden, gana la más parecida a sus ejemplos.
 * Con el modelo disponible, es el modelo quien elige con run_skill.
 */
class SkillRouter(private val registry: SkillRegistry, private val engine: SkillEngine, private val env: SkillEnvironment) {
    data class Routed(val skillId: String, val input: JsonObject)

    fun route(text: String): Routed? {
        val candidates = registry.all()
            .filter { it.localRoute && registry.availability(it.id, env).runnable }
            .mapNotNull { m -> registry.skill(m.id)?.parseText(text)?.let { Routed(m.id, it) } }
        if (candidates.size <= 1) return candidates.firstOrNull()
        val scores = registry.select(text, registry.ids().size).associate { it.id to it.score }
        return candidates.maxByOrNull { scores[it.skillId] ?: 0.0 }
    }

    suspend fun run(routed: Routed, ctx: ToolContext): SkillResult = engine.run(routed.skillId, routed.input, ctx)
}
