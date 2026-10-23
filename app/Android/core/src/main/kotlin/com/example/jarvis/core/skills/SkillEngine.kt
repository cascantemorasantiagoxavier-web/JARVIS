package com.example.jarvis.core.skills

import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.tools.ConfirmationGate
import com.example.jarvis.core.tools.PermissionGate
import com.example.jarvis.core.tools.SchemaValidator
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.ToolExecutor
import com.example.jarvis.core.tools.ToolLogEntry
import com.example.jarvis.core.tools.ToolLogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Ejecuta habilidades con las mismas garantías que las herramientas:
 * existe → implementada → entrada válida → configuración → permisos → ejecución con límite de tiempo
 * y de llamadas → salida validada contra outputSchema → registro. Nunca lanza excepciones salvo la
 * cancelación del llamador (concurrencia estructurada).
 */
class SkillEngine(
    private val registry: SkillRegistry,
    private val executor: ToolExecutor,
    private val services: SkillServices,
    private val env: SkillEnvironment,
    private val permissions: PermissionGate,
    private val confirmation: ConfirmationGate,
    private val log: ToolLogSink,
    /** Solo pruebas: se ejecuta justo antes de la habilidad (p. ej. para simular lentitud). */
    private val beforeExecute: suspend (String) -> Unit = {},
    /** Solo pruebas: sustituye el límite de tiempo del manifiesto. */
    private val timeoutOverrideMs: Long? = null,
) {
    private val runIds = AtomicLong(1)
    private val active = ConcurrentHashMap<Long, Pair<String, Job>>()

    val activeRuns: List<String> get() = active.values.map { it.first }

    /** Cancela las ejecuciones en curso (botón Detener). Devuelven CANCELLED a quien esperaba. */
    fun cancelAll(): Int = active.values.count { (_, job) -> job.cancel(CancellationException("cancelada por el usuario")); true }

    suspend fun run(id: String, input: JsonObject, toolCtx: ToolContext, progress: (String) -> Unit = {}): SkillResult {
        val started = services.time.now().toEpochMilli()
        val result = runChecked(id, input, toolCtx, progress, started)
        log.record(ToolLogEntry("skill:$id", result.ok, result.errorCode, result.durationMs, started, result.summary.take(160)))
        return result
    }

    private suspend fun runChecked(id: String, input: JsonObject, toolCtx: ToolContext, progress: (String) -> Unit, started: Long): SkillResult {
        fun out(o: SkillOutcome, ctx: SkillContext? = null) = SkillResult(
            skillId = id, status = o.status, summary = o.summary, data = if (o.status.hasData) o.data else JsonObject(emptyMap()),
            sources = o.sources, warnings = o.warnings, cards = o.cards, toolRuns = ctx?.runs.orEmpty(), errorCode = o.errorCode,
            recovery = o.recovery, durationMs = services.time.now().toEpochMilli() - started,
        )

        val manifest = registry.manifest(id)
            ?: return out(SkillOutcome(SkillStatus.NOT_FOUND, "No existe la habilidad \"$id\". ¿Quizás: ${registry.suggest(id).joinToString()}?", errorCode = "unknown_skill"))
        val skill = registry.skill(id)
        if (skill == null || manifest.maturity == Maturity.NOT_IMPLEMENTED) {
            return out(SkillOutcome(SkillStatus.NOT_IMPLEMENTED, "La habilidad ${manifest.name} todavía no está implementada: ${manifest.missing.joinToString("; ")}.", errorCode = "not_implemented"))
        }
        val problems = SchemaValidator.validate(manifest.inputSchema, input, "input")
        if (problems.isNotEmpty()) return out(Outcome.invalid("Entrada inválida para ${manifest.name}: ${problems.take(3).joinToString("; ")}."))

        val missing = registry.configMissing(manifest, env)
        if (missing.isNotEmpty()) {
            return out(Outcome.needsConfig("${manifest.name} no está disponible: ${missing.joinToString("; ")}.", recoveryFor(missing)))
        }
        for (p in manifest.requiredPermissions) {
            if (!permissions.ensure(p)) return out(Outcome.denied("${manifest.name} necesita el permiso \"$p\".", "Concede el permiso cuando JARVIS lo pida o en Ajustes del sistema."))
        }

        val ctx = SkillContext(manifest, services, env, executor, toolCtx.forSkill(id), confirmation, permissions, progress)
        val runId = runIds.getAndIncrement()
        val outcome = try {
            coroutineScope {
                val job = async { withTimeout(timeoutOverrideMs ?: (manifest.limits.timeoutSeconds * 1000L)) { beforeExecute(id); skill.execute(input, ctx) } }
                active[runId] = id to job
                try { job.await() } finally { active.remove(runId) }
            }
        } catch (e: TimeoutCancellationException) {
            SkillOutcome(SkillStatus.TIMEOUT, "${manifest.name} superó su límite de ${manifest.limits.timeoutSeconds} s y se detuvo.", errorCode = "timeout", recovery = "Prueba con una petición más acotada.")
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            SkillOutcome(SkillStatus.CANCELLED, "${manifest.name} se canceló a petición del usuario.", errorCode = "cancelled")
        } catch (e: SkillSecurityException) {
            SkillOutcome(SkillStatus.FAILED, e.message ?: "Operación no permitida.", errorCode = "security_violation")
        } catch (e: SkillLimitException) {
            SkillOutcome(SkillStatus.FAILED, e.message ?: "Límite superado.", errorCode = "limit_exceeded", recovery = "Divide la petición en partes más pequeñas.")
        } catch (e: BackendException) {
            when {
                Outcome.isConfigError(e.code) -> Outcome.needsConfig("${manifest.name}: ${e.userMessage}", "Configura el servidor de JARVIS en Ajustes (docs/SETUP.md).")
                Outcome.isNetworkError(e.code) -> Outcome.fail(e.code, "${manifest.name}: ${e.userMessage}", "Comprueba la conexión y vuelve a intentarlo.")
                else -> Outcome.fail(e.code, "${manifest.name}: ${e.userMessage}", "Inténtalo de nuevo más tarde.")
            }
        } catch (e: Exception) {
            SkillOutcome(SkillStatus.FAILED, "${manifest.name} falló: ${e.message?.take(160) ?: e::class.simpleName}", errorCode = "skill_error")
        }

        if (outcome.status.hasData) {
            val bad = SchemaValidator.validate(manifest.outputSchema, outcome.data, "data")
            if (bad.isNotEmpty()) {
                return out(SkillOutcome(SkillStatus.FAILED, "${manifest.name} produjo un resultado que no cumple su contrato: ${bad.take(2).joinToString("; ")}.", errorCode = "invalid_output"), ctx)
            }
        }
        return out(outcome, ctx)
    }

    private fun recoveryFor(missing: List<String>): String = when {
        missing.any { "servidor" in it } -> "Configura la URL y el token del servidor JARVIS en Ajustes (docs/SETUP.md)."
        missing.any { "Obsidian" in it } -> "Vincula tu bóveda en Memoria → Bóveda de Obsidian."
        else -> "Revisa la configuración en Ajustes."
    }
}
