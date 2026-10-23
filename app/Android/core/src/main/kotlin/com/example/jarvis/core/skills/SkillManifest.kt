package com.example.jarvis.core.skills

import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.tools.RiskLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Grado de implementación declarado en el manifiesto (el estado efectivo depende además de la configuración). */
enum class Maturity(val wire: String) {
    IMPLEMENTED("implemented"), PARTIAL("partial"), NOT_IMPLEMENTED("not_implemented");

    companion object {
        fun parse(v: String) = entries.firstOrNull { it.wire == v } ?: error("maturity desconocido: $v")
    }
}

/** Uso del modelo de lenguaje: none (nunca), optional (mejora el resultado), required (sin él no funciona). */
enum class ModelUse(val wire: String) {
    NONE("none"), OPTIONAL("optional"), REQUIRED("required");

    companion object {
        fun parse(v: String) = entries.firstOrNull { it.wire == v } ?: error("model desconocido: $v")
    }
}

/** Servicios externos que una habilidad puede exigir. */
object Services {
    const val JARVIS_SERVER = "jarvis_server"
    const val OBSIDIAN_VAULT = "obsidian_vault"
    const val OPEN_METEO = "open_meteo"
    const val OPEN_FOOD_FACTS = "open_food_facts"

    /** Servicios cuya ausencia se puede detectar en el teléfono (los demás solo exigen Internet). */
    val configurable = setOf(JARVIS_SERVER, OBSIDIAN_VAULT)
    val known = setOf(JARVIS_SERVER, OBSIDIAN_VAULT, OPEN_METEO, OPEN_FOOD_FACTS)

    fun label(id: String) = when (id) {
        JARVIS_SERVER -> "servidor JARVIS"
        OBSIDIAN_VAULT -> "bóveda de Obsidian vinculada"
        OPEN_METEO -> "Open-Meteo (gratuito)"
        OPEN_FOOD_FACTS -> "Open Food Facts (gratuito)"
        else -> id
    }
}

data class SkillLimits(val timeoutSeconds: Int, val maxToolCalls: Int)

data class SkillTestCase(val name: String, val input: JsonObject, val expect: List<String>)

/**
 * Contrato declarativo de una habilidad (JARVIS/shared/skills/<id>.json). Es la fuente única para
 * el catálogo, la validación de entradas y salidas, la política de riesgo y las pruebas de contrato.
 */
data class SkillManifest(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val exampleUtterances: List<String>,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val tools: List<String>,
    val requiredPermissions: List<String>,
    val optionalPermissions: List<String>,
    val requiresInternet: Boolean,
    val requiresExternalService: List<String>,
    val optionalServices: List<String>,
    val model: ModelUse,
    val cost: String,
    val risk: RiskLevel,
    val limits: SkillLimits,
    val cancellation: String,
    val errorHandling: String,
    val maturity: Maturity,
    val missing: List<String>,
    val privacy: String,
    val localRoute: Boolean,
    val testCases: List<SkillTestCase>,
) {
    /** Una línea para el catálogo breve que ve el modelo. */
    val shortDescription: String get() = description.substringBefore(". ").take(110)
}

@Serializable
private data class ManifestFile(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val exampleUtterances: List<String>,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val tools: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val optionalPermissions: List<String> = emptyList(),
    val requiresInternet: Boolean,
    val requiresExternalService: List<String> = emptyList(),
    val optionalServices: List<String> = emptyList(),
    val model: String = "none",
    val cost: String,
    val risk: String,
    val limits: LimitsFile,
    val cancellation: String,
    val errorHandling: String,
    val maturity: String,
    val missing: List<String> = emptyList(),
    val privacy: String,
    val localRoute: Boolean = false,
    val testCases: List<TestCaseFile> = emptyList(),
)

@Serializable
private data class LimitsFile(val timeoutSeconds: Int, val maxToolCalls: Int)

@Serializable
private data class TestCaseFile(val name: String, val input: JsonObject, val expect: List<String>)

object SkillCatalog {
    private val idRe = Regex("^[a-z][a-z0-9_]{2,40}$")
    private val permissionsKnown = setOf("calendar.read", "location", "camera", "microphone")

    fun parse(json: String): SkillManifest {
        val f = JarvisJson.decodeFromString(ManifestFile.serializer(), json)
        require(idRe.matches(f.id)) { "Identificador de habilidad inválido: ${f.id}" }
        require(f.exampleUtterances.size >= 3) { "${f.id}: hacen falta al menos 3 ejemplos de activación" }
        require((f.inputSchema["type"] as? JsonPrimitive)?.content == "object") { "${f.id}: inputSchema debe ser un objeto" }
        require((f.outputSchema["type"] as? JsonPrimitive)?.content == "object") { "${f.id}: outputSchema debe ser un objeto" }
        require(f.limits.timeoutSeconds in 1..300 && f.limits.maxToolCalls in 0..40) { "${f.id}: límites fuera de rango" }
        (f.requiredPermissions + f.optionalPermissions).forEach { require(it in permissionsKnown) { "${f.id}: permiso desconocido $it" } }
        (f.requiresExternalService + f.optionalServices).forEach { require(it in Services.known) { "${f.id}: servicio desconocido $it" } }
        val maturity = Maturity.parse(f.maturity)
        require(maturity == Maturity.IMPLEMENTED || f.missing.isNotEmpty()) { "${f.id}: una habilidad parcial debe declarar qué le falta" }
        require(f.testCases.isNotEmpty()) { "${f.id}: hacen falta casos de prueba" }
        return SkillManifest(
            id = f.id, name = f.name, description = f.description, category = f.category,
            exampleUtterances = f.exampleUtterances, inputSchema = f.inputSchema, outputSchema = f.outputSchema,
            tools = f.tools, requiredPermissions = f.requiredPermissions, optionalPermissions = f.optionalPermissions,
            requiresInternet = f.requiresInternet, requiresExternalService = f.requiresExternalService,
            optionalServices = f.optionalServices, model = ModelUse.parse(f.model), cost = f.cost,
            risk = RiskLevel.parse(f.risk), limits = SkillLimits(f.limits.timeoutSeconds, f.limits.maxToolCalls),
            cancellation = f.cancellation, errorHandling = f.errorHandling, maturity = maturity, missing = f.missing,
            privacy = f.privacy, localRoute = f.localRoute,
            testCases = f.testCases.map { SkillTestCase(it.name, it.input, it.expect) },
        )
    }

    /** Carga el manifiesto skills/<id>.json empaquetado en :core desde JARVIS/shared/skills. */
    fun load(id: String, loader: ClassLoader = SkillCatalog::class.java.classLoader): SkillManifest {
        val text = loader.getResourceAsStream("skills/$id.json")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("No se encontró el manifiesto skills/$id.json")
        return parse(text).also { require(it.id == id) { "El manifiesto skills/$id.json declara id ${it.id}" } }
    }
}
