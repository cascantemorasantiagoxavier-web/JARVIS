package com.example.jarvis.core.brain

import com.example.jarvis.core.intent.Intent
import com.example.jarvis.core.intent.IntentDetector
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ChatEntry
import com.example.jarvis.core.model.JarvisRequest
import com.example.jarvis.core.model.JarvisResponse
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.Role
import com.example.jarvis.core.model.ToolRun
import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.skills.SkillRouter
import com.example.jarvis.core.tools.ToolCall
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.ToolExecutor
import com.example.jarvis.core.tools.ToolRegistry
import com.example.jarvis.core.tools.ToolResult
import com.example.jarvis.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

/** Límites por estrategia: rondas de herramientas y uso de rutas locales. */
data class ModePolicy(val maxToolRounds: Int, val historyMessages: Int, val preferLocal: Boolean) {
    companion object {
        fun of(mode: AssistantMode) = when (mode) {
            AssistantMode.FAST -> ModePolicy(maxToolRounds = 1, historyMessages = 4, preferLocal = true)
            AssistantMode.BALANCED -> ModePolicy(maxToolRounds = 4, historyMessages = 10, preferLocal = true)
            AssistantMode.DEEP -> ModePolicy(maxToolRounds = 8, historyMessages = 10, preferLocal = false)
        }
    }
}

/**
 * Cerebro central de JARVIS, independiente de la interfaz y del canal (texto o voz):
 *  1. Ruta local rápida para órdenes claras (sin modelo, sin red si la herramienta es local).
 *  2. Modelo remoto con function calling y bucle de herramientas acotado por estrategia.
 *  3. Degradación honesta cuando el modelo o la red no están disponibles.
 */
class JarvisBrain(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val detector: IntentDetector,
    private val llm: () -> LlmClient?,
    private val conversation: ConversationStore,
    private val context: ContextRetriever,
    private val persona: Persona,
    private val time: TimeSource,
    /** Ruta local de habilidades cuando no hay modelo disponible (null = desactivada). */
    private val skillRouter: SkillRouter? = null,
) {
    private val callIds = AtomicLong(1)

    suspend fun handle(request: JarvisRequest, ctx: ToolContext, progress: (BrainProgress) -> Unit): JarvisResponse {
        val intent = detector.detect(request.text, ctx.working)
        val mode = request.forcedMode ?: when (intent) {
            is Intent.NeedsModel -> intent.mode
            is Intent.Tool -> AssistantMode.FAST
            else -> detector.suggestMode(request.text)
        }
        val policy = ModePolicy.of(mode)

        if (intent is Intent.Clarify) {
            return JarvisResponse(intent.question, mode = mode, usedModel = false)
        }
        if (intent is Intent.Tool && (policy.preferLocal || mode == AssistantMode.FAST) && intent.confidence >= 0.85) {
            return runDirect(intent, ctx, progress, mode)
        }

        val client = llm()
        if (client == null) return fallback(intent, request, ctx, progress, mode, "El modelo de lenguaje no está configurado.")
        return try {
            runModel(client, request, ctx, progress, mode, policy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            fallback(intent, request, ctx, progress, mode, e.userMessage)
        }
    }

    private suspend fun runDirect(intent: Intent.Tool, ctx: ToolContext, progress: (BrainProgress) -> Unit, mode: AssistantMode): JarvisResponse {
        val call = ToolCall("local-${callIds.getAndIncrement()}", intent.name, intent.args.toString())
        val (result, run) = exec(call, ctx, progress)
        return JarvisResponse(
            text = result.message,
            cards = result.cards,
            toolRuns = listOf(run),
            mode = mode,
            usedModel = false,
            isError = !result.ok,
        )
    }

    private suspend fun fallback(intent: Intent?, request: JarvisRequest, ctx: ToolContext, progress: (BrainProgress) -> Unit, mode: AssistantMode, reason: String): JarvisResponse {
        val tool = when (intent) {
            is Intent.Tool -> intent
            is Intent.NeedsModel -> intent.fallback
            else -> null
        }
        if (tool != null) {
            val r = runDirect(tool, ctx, progress, mode)
            val note = if (intent is Intent.NeedsModel) "\n\n(Sin análisis del modelo: $reason Te muestro los datos obtenidos directamente.)" else ""
            return r.copy(text = r.text + note)
        }
        skillRouter?.let { router ->
            val routed = router.route(request.text) ?: return@let
            progress(BrainProgress.ExecutingTool("run_skill"))
            val r = router.run(routed, ctx)
            progress(BrainProgress.ToolFinished("run_skill", r.ok))
            val text = r.summary + (r.warnings.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "\n\n") ?: "") + (r.recovery?.takeIf { !r.ok }?.let { "\n\n$it" } ?: "")
            return JarvisResponse(
                text = text, cards = r.cards,
                toolRuns = listOf(ToolRun("skill:${r.skillId}", r.ok, r.summary, r.durationMs, r.errorCode)) + r.toolRuns,
                mode = mode, usedModel = false, isError = !r.ok,
            )
        }
        return JarvisResponse(
            text = "No puedo responder eso ahora: $reason Sin el modelo sigo pudiendo darte la hora, hacer cálculos y conversiones, " +
                "gestionar tareas y recordatorios, guardar recuerdos, abrir aplicaciones, consultar el clima y buscar negocios si el servidor está disponible.",
            mode = mode,
            usedModel = false,
            isError = true,
        )
    }

    private suspend fun runModel(
        client: LlmClient,
        request: JarvisRequest,
        ctx: ToolContext,
        progress: (BrainProgress) -> Unit,
        mode: AssistantMode,
        policy: ModePolicy,
    ): JarvisResponse {
        progress(BrainProgress.Thinking)
        val voice = request.source == RequestSource.VOICE
        val instructions = persona.instructions(context.build(request.text, mode, ctx.working, voice))
        val tools = registry.definitionsFor(voice = false)
        val input = mutableListOf<JsonObject>()
        historyItems(policy.historyMessages, request.text).forEach { input += it }
        input += message("user", request.text)

        val runs = mutableListOf<ToolRun>()
        val cards = mutableListOf<ResultCard>()
        val toolMessages = mutableListOf<String>()
        var rounds = 0
        while (true) {
            val allowTools = rounds < policy.maxToolRounds
            val res = client.respond(LlmRequest(instructions, input, if (allowTools) tools else emptyList(), mode))
            if (res.toolCalls.isEmpty() || !allowTools) {
                val text = res.text.ifBlank {
                    if (toolMessages.isNotEmpty()) toolMessages.joinToString("\n") else "No obtuve una respuesta del modelo."
                }
                val suffix = if (res.incompleteReason == "max_output_tokens") "\n\n(Respuesta recortada por el límite de longitud.)" else ""
                return JarvisResponse(text + suffix, cards, runs, mode, usedModel = true, isError = res.text.isBlank() && toolMessages.isEmpty())
            }
            rounds++
            input += res.outputItems
            for (call in res.toolCalls) {
                val (result, run) = exec(call, ctx, progress)
                runs += run
                cards += result.cards
                toolMessages += result.message
                input += functionOutput(call.id, result.toModelOutput())
            }
            progress(BrainProgress.Thinking)
        }
    }

    private suspend fun exec(call: ToolCall, ctx: ToolContext, progress: (BrainProgress) -> Unit): Pair<ToolResult, ToolRun> {
        progress(BrainProgress.ExecutingTool(call.name))
        val started = time.now().toEpochMilli()
        val result = executor.execute(call, ctx)
        progress(BrainProgress.ToolFinished(call.name, result.ok))
        val run = ToolRun(call.name, result.ok, result.message, time.now().toEpochMilli() - started, result.errorCode)
        return result to run
    }

    /** Historial reciente sin el mensaje actual (el orquestador ya lo guardó para mostrarlo). */
    private suspend fun historyItems(limit: Int, current: String): List<JsonObject> {
        if (limit <= 0) return emptyList()
        return conversation.recent(limit + 2)
            .filter { !it.pending && !it.isError && it.text.isNotBlank() && it.role != Role.SYSTEM }
            .dropLastWhile { it.role == Role.USER && it.text == current }
            .takeLast(limit)
            .map { e: ChatEntry -> message(if (e.role == Role.USER) "user" else "assistant", e.text.take(2000)) }
    }

    companion object {
        fun message(role: String, text: String): JsonObject = jsonOf("role" to role, "content" to text)
        fun functionOutput(callId: String, output: String): JsonObject =
            jsonOf("type" to "function_call_output", "call_id" to callId, "output" to output)
    }
}
