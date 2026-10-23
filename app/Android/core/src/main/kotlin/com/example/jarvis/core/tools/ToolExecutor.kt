package com.example.jarvis.core.tools

import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Comprueba (y si procede solicita) permisos de Android como "calendar.read". */
fun interface PermissionGate {
    suspend fun ensure(permission: String): Boolean
}

/** Pide confirmación explícita al usuario para acciones sensibles. */
fun interface ConfirmationGate {
    suspend fun confirm(toolName: String, summary: String): Boolean
}

/** Registro de ejecuciones (sin argumentos ni contenido privado). */
fun interface ToolLogSink {
    fun record(entry: ToolLogEntry)
}

data class ToolLogEntry(
    val tool: String,
    val ok: Boolean,
    val errorCode: String?,
    val durationMs: Long,
    val atEpochMs: Long,
    val summary: String,
)

/**
 * Único punto de ejecución de herramientas para chat, voz y botones:
 * contrato → validación → permisos → confirmación → ejecución con timeout → resultado estructurado.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val permissions: PermissionGate,
    private val confirmation: ConfirmationGate,
    private val log: ToolLogSink,
    private val time: TimeSource,
    private val deviceTimeoutMs: Long = 15_000,
    private val backendTimeoutMs: Long = 75_000,
) {
    suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val started = time.now().toEpochMilli()
        val result = runChecked(call, ctx)
        val duration = time.now().toEpochMilli() - started
        log.record(
            ToolLogEntry(
                tool = call.name,
                ok = result.ok,
                errorCode = result.errorCode,
                durationMs = duration,
                atEpochMs = started,
                summary = result.message.take(160),
            ),
        )
        return result
    }

    private suspend fun runChecked(call: ToolCall, ctx: ToolContext): ToolResult {
        val def = registry.definition(call.name)
            ?: return ToolResult.error("unknown_tool", "La herramienta \"${call.name}\" no existe.")
        val handler = registry.handler(call.name)
            ?: return ToolResult.error("not_implemented", "La herramienta \"${call.name}\" no está disponible en este dispositivo.")
        val args = ToolRegistry.parseArguments(call.argumentsJson)
            ?: return ToolResult.error("invalid_arguments", "Los argumentos de ${call.name} no son JSON válido.")
        val problems = SchemaValidator.validate(def.parameters, args)
        if (problems.isNotEmpty()) {
            return ToolResult.error("invalid_arguments", "Argumentos inválidos para ${call.name}: ${problems.take(3).joinToString("; ")}")
        }
        for (p in def.permissions) {
            if (!permissions.ensure(p)) {
                return ToolResult.error("permission_denied", "Falta el permiso necesario ($p). Puedes concederlo en Ajustes.")
            }
        }
        if (needsConfirmation(def, ctx)) {
            val ok = confirmation.confirm(def.name, describeForConfirmation(def, args, ctx))
            if (!ok) return ToolResult.error("not_confirmed", "Acción cancelada: no fue confirmada por el usuario.")
        }
        val timeout = def.timeoutMs ?: if (def.site == ToolSite.BACKEND) backendTimeoutMs else deviceTimeoutMs
        return try {
            withTimeout(timeout) { handler.execute(args, ctx) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "La herramienta ${call.name} tardó demasiado y se canceló.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            ToolResult.error(e.code, e.userMessage)
        } catch (e: Exception) {
            ToolResult.error("tool_failed", "La herramienta ${call.name} falló: ${e.message?.take(160) ?: e::class.simpleName}")
        }
    }

    private fun describeForConfirmation(def: ToolDefinition, args: JsonObject, ctx: ToolContext): String {
        // Los contenidos grandes (imágenes, audio, documentos) no se muestran en el diálogo.
        val shown = JsonObject(args.mapValues { (_, v) -> if (v.toString().length > 400) JsonPrimitive("(${v.toString().length} caracteres)") else v })
        val via = ctx.viaSkill?.let { " (habilidad $it)" }.orEmpty()
        return "${def.description.substringBefore('.')}$via — $shown"
    }

    companion object {
        /**
         * Política de confirmación por riesgo:
         *  - SENSITIVE o `confirm: true`: siempre.
         *  - EXTERNAL: siempre, salvo que el usuario haya tocado directamente ese botón (BUTTON sin habilidad).
         *  - READ y LOCAL_WRITE: no (las habilidades pueden pedirla para borrados propios).
         */
        fun needsConfirmation(def: ToolDefinition, ctx: ToolContext): Boolean = when {
            def.requiresConfirmation || def.risk == RiskLevel.SENSITIVE -> true
            def.risk == RiskLevel.EXTERNAL -> !(ctx.source == RequestSource.BUTTON && ctx.viaSkill == null)
            else -> false
        }
    }
}
