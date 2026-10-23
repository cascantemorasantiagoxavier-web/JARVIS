package com.example.jarvis.core.skills

import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.text.TextNormalizer
import com.example.jarvis.core.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject

/** Estado efectivo de una habilidad en este teléfono, con los motivos. */
enum class SkillState(val label: String) {
    IMPLEMENTADA("IMPLEMENTADA"),
    PARCIAL("PARCIAL"),
    REQUIERE_CONFIGURACION("REQUIERE_CONFIGURACIÓN"),
    NO_IMPLEMENTADA("NO_IMPLEMENTADA"),
}

data class SkillAvailability(val state: SkillState, val reasons: List<String>) {
    val runnable: Boolean get() = state == SkillState.IMPLEMENTADA || state == SkillState.PARCIAL
}

data class SkillMatch(val id: String, val score: Double)

/**
 * Registro de habilidades: descubrir (catálogo y estado), seleccionar (por lenguaje natural) y
 * describir. Comprueba al registrar que cada habilidad solo declare herramientas existentes y que
 * su nivel de riesgo no sea menor que el de las herramientas que usa.
 */
class SkillRegistry(private val tools: ToolRegistry) {
    private val manifests = linkedMapOf<String, SkillManifest>()
    private val impls = mutableMapOf<String, Skill>()
    private val index = mutableMapOf<String, SkillTokens>()

    private class SkillTokens(val examples: List<Set<String>>, val description: Set<String>)

    fun register(skill: Skill, manifest: SkillManifest = SkillCatalog.load(skill.id)) {
        require(manifest.id == skill.id) { "Manifiesto ${manifest.id} no corresponde a ${skill.id}" }
        add(manifest)
        impls[skill.id] = skill
    }

    /** Habilidad declarada sin implementación (aparece como NO_IMPLEMENTADA, nunca se ejecuta). */
    fun declare(manifest: SkillManifest) = add(manifest)

    private fun add(manifest: SkillManifest) {
        require(manifest.id !in manifests) { "Habilidad duplicada: ${manifest.id}" }
        for (t in manifest.tools) {
            val def = tools.definition(t) ?: error("${manifest.id} declara la herramienta inexistente $t")
            require(manifest.risk >= def.risk) { "${manifest.id} tiene riesgo ${manifest.risk} menor que su herramienta $t (${def.risk})" }
        }
        manifests[manifest.id] = manifest
        index[manifest.id] = SkillTokens(
            examples = manifest.exampleUtterances.map { tokens(it) },
            description = tokens(manifest.name + " " + manifest.description + " " + manifest.id.replace('_', ' ')),
        )
    }

    fun manifest(id: String): SkillManifest? = manifests[id]
    fun skill(id: String): Skill? = impls[id]
    fun all(): List<SkillManifest> = manifests.values.toList()
    fun ids(): List<String> = manifests.keys.toList()

    fun availability(id: String, env: SkillEnvironment): SkillAvailability {
        val m = manifests[id] ?: return SkillAvailability(SkillState.NO_IMPLEMENTADA, listOf("No existe"))
        if (m.maturity == Maturity.NOT_IMPLEMENTED || id !in impls) return SkillAvailability(SkillState.NO_IMPLEMENTADA, m.missing)
        val missing = configMissing(m, env)
        if (missing.isNotEmpty()) return SkillAvailability(SkillState.REQUIERE_CONFIGURACION, missing)
        return if (m.maturity == Maturity.PARTIAL) SkillAvailability(SkillState.PARCIAL, m.missing) else SkillAvailability(SkillState.IMPLEMENTADA, emptyList())
    }

    fun configMissing(m: SkillManifest, env: SkillEnvironment): List<String> {
        val out = m.requiresExternalService.filter { it in Services.configurable && !env.has(it) }.map { "Requiere ${Services.label(it)}" }.toMutableList()
        if (m.model == ModelUse.REQUIRED && !env.serverConfigured() && Services.JARVIS_SERVER !in m.requiresExternalService) out += "Requiere el servidor JARVIS (modelo de IA)"
        return out
    }

    /** Selección por lenguaje natural: similitud con los ejemplos y con la descripción. */
    fun select(text: String, limit: Int = 3): List<SkillMatch> {
        val q = tokens(text)
        if (q.isEmpty()) return emptyList()
        return manifests.keys.map { id ->
            val t = index.getValue(id)
            val ex = t.examples.maxOfOrNull { dice(q, it) } ?: 0.0
            val desc = q.count { term -> t.description.any { related(term, it) } }.toDouble() / q.size
            SkillMatch(id, 0.75 * ex + 0.25 * desc)
        }.sortedByDescending { it.score }.take(limit)
    }

    /** La mejor coincidencia solo si es clara (umbral y margen sobre la segunda). */
    fun confidentMatch(text: String, minScore: Double = 0.42, margin: Double = 0.07): SkillMatch? {
        val top = select(text, 2)
        val best = top.firstOrNull() ?: return null
        val second = top.getOrNull(1)?.score ?: 0.0
        return best.takeIf { it.score >= minScore && it.score - second >= margin }
    }

    /**
     * Catálogo breve para el modelo: las más relevantes con una línea y el resto solo por id.
     * Nunca incluye esquemas completos (se piden con describe_skill).
     */
    fun briefCatalog(env: SkillEnvironment, query: String?, detailed: Int = 6): String {
        val states = manifests.keys.associateWith { availability(it, env) }
        val runnable = manifests.keys.filter { states.getValue(it).runnable }
        val ranked = if (query.isNullOrBlank()) emptyList() else select(query, detailed).filter { it.score >= 0.2 && it.id in runnable }.map { it.id }
        val sb = StringBuilder("Habilidades de JARVIS (usa run_skill con el id; describe_skill da los parámetros exactos):\n")
        ranked.forEach { id -> val m = manifests.getValue(id); sb.append("- ").append(id).append(": ").append(m.shortDescription).append('\n') }
        val rest = runnable.filter { it !in ranked }
        if (rest.isNotEmpty()) sb.append(if (ranked.isEmpty()) "Disponibles: " else "Otras disponibles: ").append(rest.joinToString(", ")).append('\n')
        val config = manifests.keys.filter { states.getValue(it).state == SkillState.REQUIERE_CONFIGURACION }
        if (config.isNotEmpty()) sb.append("Requieren configuración (díselo al usuario si las pide): ")
            .append(config.joinToString(", ") { "$it (${states.getValue(it).reasons.joinToString("; ")})" }).append('\n')
        val none = manifests.keys.filter { states.getValue(it).state == SkillState.NO_IMPLEMENTADA }
        if (none.isNotEmpty()) sb.append("No implementadas: ").append(none.joinToString(", ")).append('\n')
        return sb.toString().trim()
    }

    fun describe(id: String, env: SkillEnvironment): JsonObject? {
        val m = manifests[id] ?: return null
        val a = availability(id, env)
        return jsonOf(
            "id" to m.id, "name" to m.name, "description" to m.description, "category" to m.category,
            "state" to a.state.label, "reasons" to a.reasons, "examples" to m.exampleUtterances,
            "input_schema" to m.inputSchema, "output_schema" to m.outputSchema,
            "required_permissions" to m.requiredPermissions, "optional_permissions" to m.optionalPermissions,
            "requires_internet" to m.requiresInternet, "requires_external_service" to m.requiresExternalService.map(Services::label),
            "optional_services" to m.optionalServices.map(Services::label), "model" to m.model.wire, "cost" to m.cost,
            "risk" to m.risk.wire, "timeout_seconds" to m.limits.timeoutSeconds, "max_tool_calls" to m.limits.maxToolCalls,
            "cancellation" to m.cancellation, "error_handling" to m.errorHandling, "missing" to m.missing, "privacy" to m.privacy,
        )
    }

    fun suggest(unknown: String): List<String> {
        val q = TextNormalizer.fold(unknown)
        return manifests.keys.sortedBy { levenshtein(it, q) }.take(3)
    }

    companion object {
        fun tokens(text: String): Set<String> = TextNormalizer.searchTokens(text).toSet()

        /** Mismo término o raíz compartida (≥ 5 letras): "inventario" ~ "inventarios", "factur" ~ "facturas". */
        fun related(a: String, b: String): Boolean =
            a == b || (a.length >= 5 && b.length >= 5 && (a.startsWith(b) || b.startsWith(a) || a.take(6) == b.take(6)))

        fun dice(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val hits = a.count { x -> b.any { related(x, it) } }
            return 2.0 * hits / (a.size + b.size)
        }

        private fun levenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
