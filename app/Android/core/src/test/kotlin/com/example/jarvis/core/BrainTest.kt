package com.example.jarvis.core

import com.example.jarvis.core.brain.LlmClient
import com.example.jarvis.core.brain.LlmRequest
import com.example.jarvis.core.brain.LlmResult
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.CoreState
import com.example.jarvis.core.model.JarvisRequest
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.Role
import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.orchestrator.JarvisOrchestrator
import com.example.jarvis.core.tools.ToolCall
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.WorkingContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modelo simulado: devuelve respuestas predefinidas y registra lo que recibe. */
class ScriptedLlm(private val script: (LlmRequest, Int) -> LlmResult) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    override suspend fun respond(request: LlmRequest): LlmResult {
        requests += request
        return script(request, requests.size)
    }
}

class BrainTest {
    private fun ctx(kit: TestKit, source: RequestSource = RequestSource.TEXT) = ToolContext(source, kit.ui, WorkingContext())

    @Test fun fastLocalPathDoesNotCallTheModel() = runTest {
        val llm = ScriptedLlm { _, _ -> error("no debería llamarse") }
        val kit = TestKit(llm = llm)
        val r = kit.brain.handle(JarvisRequest("¿Qué hora es?"), ctx(kit)) {}
        assertEquals("Son las 07:42 del lunes 29 de septiembre de 2025.", r.text)
        assertFalse(r.usedModel)
        assertEquals(AssistantMode.FAST, r.mode)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun rememberThenRecallWithoutModel() = runTest {
        val kit = TestKit()
        val saved = kit.brain.handle(JarvisRequest("Recuerda que prefiero respuestas directas"), ctx(kit)) {}
        assertEquals("Guardado en memoria: \"Prefiero respuestas directas\".", saved.text)
        assertEquals(1, kit.memoryRepo.items.size)
        val recalled = kit.brain.handle(JarvisRequest("¿Cuál es mi preferencia sobre las respuestas?"), ctx(kit)) {}
        assertTrue(recalled.text.contains("Prefiero respuestas directas"))
    }

    @Test fun modelToolLoopExecutesRealToolsAndAnswers() = runTest {
        val llm = ScriptedLlm { req, n ->
            when (n) {
                1 -> LlmResult("", listOf(ToolCall("call_1", "calculate", """{"expression":"1250*3"}""")), listOf(jsonOf("type" to "function_call", "call_id" to "call_1", "name" to "calculate", "arguments" to "{}")))
                else -> {
                    val out = req.input.last()
                    assertEquals("function_call_output", (out["type"] as kotlinx.serialization.json.JsonPrimitive).content)
                    assertTrue(out.toString().contains("3 750"))
                    LlmResult("Serían 3 750 colones, jefe.", emptyList(), emptyList())
                }
            }
        }
        val kit = TestKit(llm = llm)
        val progress = mutableListOf<String>()
        val r = kit.brain.handle(JarvisRequest("Si cada unidad cuesta 1250 colones y compro tres, ¿cuánto pago en total aproximadamente?"), ctx(kit)) { progress += it::class.simpleName!! }
        assertEquals("Serían 3 750 colones, jefe.", r.text)
        assertTrue(r.usedModel)
        assertEquals(listOf("calculate"), r.toolRuns.map { it.name })
        assertTrue(r.toolRuns.single().ok)
        assertEquals(listOf("Thinking", "ExecutingTool", "ToolFinished", "Thinking"), progress)
        val first = llm.requests.first()
        assertTrue("las instrucciones incluyen la personalidad", first.instructions.contains("J.A.R.V.I.S."))
        assertTrue("y la fecha real", first.instructions.contains("lunes 29 de septiembre de 2025"))
        assertTrue(first.tools.any { it.name == "business_search" })
        assertFalse("solo voz no se ofrece en texto", first.tools.any { it.name == "end_conversation" })
    }

    @Test fun invalidToolArgumentsFromModelAreRejectedAndReported() = runTest {
        val llm = ScriptedLlm { req, n ->
            if (n == 1) LlmResult("", listOf(ToolCall("c1", "forget", """{"memory_id":"todos"}""")), emptyList())
            else LlmResult("No pude borrar: argumentos inválidos.", emptyList(), emptyList())
        }
        val kit = TestKit(llm = llm)
        kit.memory.remember("Dato importante", com.example.jarvis.core.memory.MemoryKind.FACT)
        val r = kit.brain.handle(JarvisRequest("¿Puedes ayudarme a limpiar un recuerdo antiguo que ya no sirve?"), ctx(kit)) {}
        assertEquals("invalid_arguments", r.toolRuns.single().errorCode)
        assertEquals(1, kit.memoryRepo.items.size)
    }

    @Test fun deepModeAllowsMoreRoundsButIsBounded() = runTest {
        var calls = 0
        val llm = ScriptedLlm { req, _ ->
            calls++
            if (req.tools.isEmpty()) LlmResult("Resumen final con fuentes.", emptyList(), emptyList())
            else LlmResult("", listOf(ToolCall("c$calls", "get_datetime", "{}")), emptyList())
        }
        val kit = TestKit(llm = llm)
        val r = kit.brain.handle(JarvisRequest("Investiga a fondo y compara opciones de transporte marítimo", forcedMode = AssistantMode.DEEP), ctx(kit)) {}
        assertEquals(AssistantMode.DEEP, r.mode)
        assertEquals(8, r.toolRuns.size)
        assertEquals("Resumen final con fuentes.", r.text)
        val fast = TestKit(llm = ScriptedLlm { req, _ -> if (req.tools.isEmpty()) LlmResult("fin", emptyList(), emptyList()) else LlmResult("", listOf(ToolCall("x", "get_datetime", "{}")), emptyList()) })
        assertEquals(1, fast.brain.handle(JarvisRequest("hola", forcedMode = AssistantMode.FAST), ctx(fast)) {}.toolRuns.size)
    }

    @Test fun modelUnavailableFallsBackHonestly() = runTest {
        val kit = TestKit(llm = null)
        val r = kit.brain.handle(JarvisRequest("¿Qué opinas del arte moderno?"), ctx(kit)) {}
        assertTrue(r.isError)
        assertTrue(r.text.contains("no está configurado"))
        val failing = TestKit(llm = ScriptedLlm { _, _ -> throw BackendException("upstream_quota", "La cuenta de OpenAI no tiene saldo o cuota disponible.") })
        val q = failing.brain.handle(JarvisRequest("Cuéntame algo interesante"), ctx(failing)) {}
        assertTrue(q.text.contains("no tiene saldo"))
    }

    @Test fun deepFallbackUsesRealWebSearchWhenModelFails() = runBlocking {
        // El backend no existe en el puerto 9: la búsqueda falla y se informa, sin inventar resultados.
        val kit = TestKit(llm = null)
        val r = kit.brain.handle(JarvisRequest("Investiga los requisitos para importar café"), ctx(kit)) {}
        assertEquals("web_search", r.toolRuns.single().name)
        assertFalse(r.toolRuns.single().ok)
        assertTrue(r.text.contains("Sin análisis del modelo"))
        assertTrue(r.text, r.text.contains("servidor"))
        assertTrue(r.cards.isEmpty())
    }

    @Test fun historyIsSharedAndExcludesCurrentMessage() = runTest {
        val llm = ScriptedLlm { _, _ -> LlmResult("Respuesta", emptyList(), emptyList()) }
        val kit = TestKit(llm = llm)
        kit.conversation.append(com.example.jarvis.core.model.ChatEntry(0, Role.USER, "Me llamo Santiago", kit.time.now(), RequestSource.VOICE))
        kit.conversation.append(com.example.jarvis.core.model.ChatEntry(0, Role.ASSISTANT, "Encantado, Santiago.", kit.time.now(), RequestSource.VOICE))
        kit.conversation.append(com.example.jarvis.core.model.ChatEntry(0, Role.USER, "¿Cómo me llamo según lo que te dije antes?", kit.time.now()))
        kit.brain.handle(JarvisRequest("¿Cómo me llamo según lo que te dije antes?"), ctx(kit)) {}
        val input = llm.requests.single().input.map { (it["content"] as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals(listOf("Me llamo Santiago", "Encantado, Santiago.", "¿Cómo me llamo según lo que te dije antes?"), input)
    }

    @Test fun privateMemoriesNeverReachTheModel() = runTest {
        val llm = ScriptedLlm { _, _ -> LlmResult("ok", emptyList(), emptyList()) }
        val kit = TestKit(llm = llm)
        kit.memory.remember("La clave del portón es 4821", com.example.jarvis.core.memory.MemoryKind.FACT, isPrivate = true)
        kit.memory.remember("Prefiero respuestas directas", com.example.jarvis.core.memory.MemoryKind.PREFERENCE)
        kit.brain.handle(JarvisRequest("¿Me ayudas a recordar la clave del portón de la casa?"), ctx(kit)) {}
        val instr = llm.requests.single().instructions
        assertFalse(instr.contains("4821"))
        assertTrue(instr.contains("Prefiero respuestas directas"))
        kit.privacy = kit.privacy.copy(shareMemoriesWithModel = false)
        kit.brain.handle(JarvisRequest("¿Me ayudas con otra cosa distinta, por favor, ahora mismo?"), ctx(kit)) {}
        assertFalse(llm.requests.last().instructions.contains("Prefiero respuestas directas"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun orchestratorStatesHistoryAndCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val llm = ScriptedLlm { _, _ -> LlmResult("Hola, jefe.", emptyList(), emptyList()) }
        val kit = TestKit(llm = object : LlmClient {
            override suspend fun respond(request: LlmRequest): LlmResult { gate.await(); return llm.respond(request) }
        })
        val orch = JarvisOrchestrator(kit.brain, kit.conversation, this, kit.time, WorkingContext(), kit.ui)
        val states = mutableListOf<CoreState>()
        val watcher = backgroundScope.launch { orch.activity.collect { states += it.state } }
        orch.submit("¿Qué opinas de los lunes por la mañana?")
        advanceUntilIdle()
        assertEquals(CoreState.THINKING, orch.activity.value.state)
        assertTrue(orch.entries.value.last().pending)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Hola, jefe.", orch.entries.value.last().text)
        assertEquals(CoreState.IDLE, orch.activity.value.state)
        assertEquals(2, kit.conversation.entries.size)

        // Cancelación de una operación en curso
        val slow = CompletableDeferred<Unit>()
        val kit2 = TestKit(llm = object : LlmClient { override suspend fun respond(request: LlmRequest): LlmResult { slow.await(); return LlmResult("tarde", emptyList(), emptyList()) } })
        val orch2 = JarvisOrchestrator(kit2.brain, kit2.conversation, this, kit2.time, WorkingContext(), kit2.ui)
        orch2.submit("Explícame la teoría de la relatividad con detalle")
        advanceUntilIdle()
        orch2.cancel()
        advanceUntilIdle()
        assertEquals("Operación cancelada.", orch2.entries.value.last().text)
        assertEquals(CoreState.IDLE, orch2.activity.value.state)
        watcher.cancel()
        assertTrue(states.contains(CoreState.THINKING))
    }

    /** E/S real (conexión rechazada): runBlocking, porque runTest usa tiempo virtual y adelantaría los timeouts. */
    @Test fun businessSearchFailureIsReportedNotInvented() = runBlocking {
        val kit = TestKit()
        val r = kit.brain.handle(JarvisRequest("Busca supermercados en San José, Costa Rica"), ctx(kit)) {}
        assertEquals("business_search", r.toolRuns.single().name)
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("servidor"))
        assertTrue(r.cards.none { it is ResultCard.Businesses })
    }

    @Test fun voiceAndChatRequestsShareTheSamePipeline() = runTest {
        val kit = TestKit()
        val text = kit.brain.handle(JarvisRequest("convierte 10 km a millas", RequestSource.TEXT), ctx(kit)) {}
        val voice = kit.brain.handle(JarvisRequest("JARVIS, convierte 10 km a millas", RequestSource.VOICE), ctx(kit, RequestSource.VOICE)) {}
        assertEquals(text.text, voice.text)
        assertEquals(text.toolRuns.map { it.name }, voice.toolRuns.map { it.name })
    }
}
