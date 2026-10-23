package com.example.jarvis.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Validador del subconjunto de JSON Schema usado en tools.json:
 * type, properties, required, additionalProperties=false, enum, minimum/maximum,
 * minLength/maxLength, items, maxItems. Los argumentos que propone un modelo de IA
 * se validan siempre antes de ejecutar cualquier herramienta.
 */
object SchemaValidator {
    fun validate(schema: JsonObject, value: JsonElement, path: String = "args"): List<String> {
        val errors = mutableListOf<String>()
        check(schema, value, path, errors)
        return errors
    }

    private fun check(schema: JsonObject, value: JsonElement, path: String, errors: MutableList<String>) {
        val type = (schema["type"] as? JsonPrimitive)?.content
        when (type) {
            "object" -> {
                if (value !is JsonObject) return errors.plusAssign("$path debe ser un objeto")
                val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                for (r in required) if (value[r] == null || value[r] is JsonNull) errors += "$path.$r es obligatorio"
                val additional = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull ?: true
                for ((k, v) in value) {
                    val sub = props[k] as? JsonObject
                    if (sub == null) {
                        if (!additional) errors += "$path.$k no está permitido"
                    } else if (v !is JsonNull) {
                        check(sub, v, "$path.$k", errors)
                    }
                }
            }
            "array" -> {
                if (value !is JsonArray) return errors.plusAssign("$path debe ser una lista")
                (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { if (value.size > it) errors += "$path admite como máximo $it elementos" }
                val items = schema["items"] as? JsonObject
                if (items != null) value.forEachIndexed { i, v -> check(items, v, "$path[$i]", errors) }
            }
            "string" -> {
                val p = value as? JsonPrimitive
                if (p == null || !p.isString) return errors.plusAssign("$path debe ser texto")
                val s = p.content
                (schema["minLength"] as? JsonPrimitive)?.intOrNull?.let { if (s.length < it) errors += "$path es demasiado corto" }
                (schema["maxLength"] as? JsonPrimitive)?.intOrNull?.let { if (s.length > it) errors += "$path es demasiado largo" }
                // Como JSON Schema: búsqueda sin anclar; el patrón lleva ^…$ si exige coincidencia completa.
                (schema["pattern"] as? JsonPrimitive)?.content?.let { if (!Regex(it).containsMatchIn(s)) errors += "$path no tiene el formato esperado" }
                checkEnum(schema, p, path, errors)
            }
            "integer" -> {
                val p = value as? JsonPrimitive
                val n = p?.takeIf { !it.isString }?.longOrNull
                if (n == null) return errors.plusAssign("$path debe ser un número entero")
                checkRange(schema, n.toDouble(), path, errors)
            }
            "number" -> {
                val p = value as? JsonPrimitive
                val n = p?.takeIf { !it.isString }?.doubleOrNull
                if (n == null || n.isNaN() || n.isInfinite()) return errors.plusAssign("$path debe ser un número")
                checkRange(schema, n, path, errors)
            }
            "boolean" -> {
                val p = value as? JsonPrimitive
                if (p == null || p.isString || p.booleanOrNull == null) errors += "$path debe ser verdadero o falso"
            }
        }
    }

    private fun checkRange(schema: JsonObject, n: Double, path: String, errors: MutableList<String>) {
        (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (n < it) errors += "$path debe ser ≥ ${fmt(it)}" }
        (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { if (n > it) errors += "$path debe ser ≤ ${fmt(it)}" }
    }

    private fun checkEnum(schema: JsonObject, p: JsonPrimitive, path: String, errors: MutableList<String>) {
        val options = (schema["enum"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return
        if (p.content !in options) errors += "$path debe ser uno de: ${options.joinToString()}"
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
}
