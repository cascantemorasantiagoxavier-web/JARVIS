package com.example.jarvis.core.tools

import com.example.jarvis.core.json.JarvisJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Dónde se ejecuta una herramienta. */
enum class ToolSite { DEVICE, BACKEND }

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val site: ToolSite,
    val requiresConfirmation: Boolean,
    val permissions: List<String>,
    val voiceOnly: Boolean,
)

@Serializable
private data class CatalogFile(val version: Int, val tools: List<CatalogTool>)

@Serializable
private data class CatalogTool(
    val name: String,
    val description: String,
    val site: String,
    val confirm: Boolean = false,
    val permissions: List<String> = emptyList(),
    val voiceOnly: Boolean = false,
    val parameters: JsonObject,
)

/** Carga el contrato único de herramientas (JARVIS/shared/tools.json, empaquetado en :core). */
object ToolCatalog {
    const val RESOURCE = "tools.json"

    fun parse(json: String): List<ToolDefinition> {
        val file = JarvisJson.decodeFromString(CatalogFile.serializer(), json)
        val names = mutableSetOf<String>()
        return file.tools.map { t ->
            require(Regex("^[a-z][a-z0-9_]{1,63}$").matches(t.name)) { "Nombre de herramienta inválido: ${t.name}" }
            require(names.add(t.name)) { "Herramienta duplicada: ${t.name}" }
            require(t.parameters["type"]?.toString() == "\"object\"") { "Los parámetros de ${t.name} deben ser un objeto" }
            ToolDefinition(
                name = t.name,
                description = t.description,
                parameters = t.parameters,
                site = when (t.site) {
                    "device" -> ToolSite.DEVICE
                    "backend" -> ToolSite.BACKEND
                    else -> error("site desconocido en ${t.name}: ${t.site}")
                },
                requiresConfirmation = t.confirm,
                permissions = t.permissions,
                voiceOnly = t.voiceOnly,
            )
        }
    }

    fun loadFromResources(loader: ClassLoader = ToolCatalog::class.java.classLoader): List<ToolDefinition> {
        val text = loader.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("No se encontró $RESOURCE en los recursos de :core")
        return parse(text)
    }
}
