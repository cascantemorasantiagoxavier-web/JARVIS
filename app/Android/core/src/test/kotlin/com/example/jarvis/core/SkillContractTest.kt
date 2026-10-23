package com.example.jarvis.core

import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillCatalog
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillEngine
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillState
import com.example.jarvis.core.skills.SkillStatus
import com.example.jarvis.core.tools.ConfirmationGate
import com.example.jarvis.core.tools.PermissionGate
import com.example.jarvis.core.tools.RiskLevel
import com.example.jarvis.core.tools.SchemaValidator
import com.example.jarvis.core.tools.ToolContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato común a TODAS las habilidades registradas (se aplica solo a cada habilidad nueva):
 * manifiesto válido, activación por ejemplos, entrada válida/inválida, salida estructurada,
 * sin servidor, sin permisos, cancelación, límite de tiempo y seguridad.
 */
class SkillContractTest {
    private fun ctx(kit: TestKit, source: RequestSource = RequestSource.TEXT) = ToolContext(source, kit.ui, com.example.jarvis.core.tools.WorkingContext())

    @Test fun everyManifestFileHasAnImplementationAndViceVersa() {
        val kit = TestKit()
        val dir = File("../../../shared/skills")
        val files = dir.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals("manifiestos sin implementación o al revés", files, kit.skills.ids().toSet())
        files.forEach { SkillCatalog.load(it) }
    }

    @Test fun riskOfEverySkillCoversItsTools() {
        val kit = TestKit()
        for (m in kit.skills.all()) for (t in m.tools) {
            assertTrue("${m.id} usa $t", m.risk >= kit.registry.definition(t)!!.risk)
        }
    }

    @Test fun everyExampleUtteranceActivatesItsSkill() {
        val kit = TestKit()
        val failures = mutableListOf<String>()
        for (m in kit.skills.all()) for (ex in m.exampleUtterances) {
            val top = kit.skills.select(ex, 2)
            if (top.firstOrNull()?.id != m.id) failures += "\"$ex\" → ${top.map { "${it.id}:${"%.2f".format(it.score)}" }} (esperado ${m.id})"
        }
        assertTrue("Activación fallida:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun declaredTestCasesProduceExpectedStatusAndValidOutput() = runBlocking {
        val failures = mutableListOf<String>()
        for (id in TestKit().skills.ids()) {
            val kit = TestKit()
            for (case in kit.skills.manifest(id)!!.testCases) {
                val r = kit.engine.run(id, case.input, ctx(kit))
                if (r.status.wire !in case.expect) failures += "$id/${case.name}: ${r.status} — ${r.summary}"
                if (r.status.hasData) {
                    val bad = SchemaValidator.validate(kit.skills.manifest(id)!!.outputSchema, r.data)
                    if (bad.isNotEmpty()) failures += "$id/${case.name}: salida inválida $bad"
                    assertTrue("$id: resumen vacío", r.summary.isNotBlank())
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun invalidInputIsRejectedBeforeExecution() = runBlocking {
        val kit = TestKit()
        for (m in kit.skills.all()) {
            val case = m.testCases.first().input
            val injected = JsonObject(case + ("__campo_no_declarado" to JsonPrimitive("x")))
            val r = kit.engine.run(m.id, injected, ctx(kit))
            assertEquals("${m.id} acepta campos no declarados", SkillStatus.INVALID_INPUT, r.status)
            assertTrue("${m.id}: no debe ejecutar herramientas con entrada inválida", r.toolRuns.isEmpty())
            val required = (m.inputSchema["required"] as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
            if (required.isNotEmpty()) {
                val missing = JsonObject(case - required.first())
                assertEquals("${m.id} sin ${required.first()}", SkillStatus.INVALID_INPUT, kit.engine.run(m.id, missing, ctx(kit)).status)
            }
        }
    }

    @Test fun withoutServerSkillsDegradeOrAskForConfiguration() = runBlocking {
        for (id in TestKit().skills.ids()) {
            val kit = TestKit() // backend inalcanzable y sin configurar
            kit.serverConfigured = false
            val m = kit.skills.manifest(id)!!
            val r = kit.engine.run(id, m.testCases.first().input, ctx(kit))
            assertTrue("$id sin servidor → ${r.status}: ${r.summary}", r.status in setOf(SkillStatus.COMPLETED, SkillStatus.PARTIAL, SkillStatus.NEEDS_CONFIGURATION, SkillStatus.FAILED, SkillStatus.CANCELLED))
            if ("jarvis_server" in m.requiresExternalService) {
                assertEquals("$id requiere servidor", SkillStatus.NEEDS_CONFIGURATION, r.status)
                assertEquals(SkillState.REQUIERE_CONFIGURACION, kit.skills.availability(id, kit.env).state)
                assertTrue("$id debe explicar cómo recuperarse", !r.recovery.isNullOrBlank())
            }
            if (r.status == SkillStatus.FAILED) assertTrue("$id falla sin explicar recuperación: ${r.summary}", !r.recovery.isNullOrBlank())
        }
    }

    @Test fun missingRequiredPermissionStopsTheSkill() = runBlocking {
        for (m in TestKit().skills.all().filter { it.requiredPermissions.isNotEmpty() }) {
            val kit = TestKit(permissionGranted = false)
            kit.serverConfigured = true
            val r = kit.engine.run(m.id, m.testCases.first().input, ctx(kit))
            assertEquals(m.id, SkillStatus.PERMISSION_DENIED, r.status)
            assertTrue(r.toolRuns.isEmpty())
        }
    }

    @Test fun cancellationReturnsCancelledWithoutPartialWrites() = runBlocking {
        for (id in TestKit().skills.ids()) {
            val kit = TestKit()
            val engine = SkillEngine(kit.skills, kit.executor, kit.skillServices, kit.env, PermissionGate { true }, ConfirmationGate { _, _ -> true }, {}, beforeExecute = { delay(5_000) })
            val before = kit.store.list("inventory.items").size + kit.taskRepo.items.size + kit.device.actions.size
            val run = async { engine.run(id, kit.skills.manifest(id)!!.testCases.first().input, ctx(kit)) }
            delay(100)
            assertEquals(1, engine.cancelAll())
            val r = run.await()
            assertEquals(id, SkillStatus.CANCELLED, r.status)
            assertEquals("$id dejó cambios tras cancelar", before, kit.store.list("inventory.items").size + kit.taskRepo.items.size + kit.device.actions.size)
        }
    }

    @Test fun timeLimitStopsSlowSkills() = runBlocking {
        val kit = TestKit()
        val engine = SkillEngine(kit.skills, kit.executor, kit.skillServices, kit.env, PermissionGate { true }, ConfirmationGate { _, _ -> true }, {}, beforeExecute = { delay(2_000) }, timeoutOverrideMs = 100)
        val id = kit.skills.ids().first()
        assertEquals(SkillStatus.TIMEOUT, engine.run(id, kit.skills.manifest(id)!!.testCases.first().input, ctx(kit)).status)
    }

    @Test fun skillCannotCallUndeclaredToolsOrDoExternalActionsSilently() = runBlocking {
        val kit = TestKit()
        val rogue = object : Skill {
            override val id = "writing_editor"
            override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
                ctx.tool("call_phone", jsonOf("business_id" to "osm:node:1"))
                return Outcome.done("no debería llegar aquí", JsonObject(emptyMap()))
            }
        }
        val reg = com.example.jarvis.core.skills.SkillRegistry(kit.registry)
        reg.register(rogue, kit.skills.manifest("writing_editor")!!)
        val engine = SkillEngine(reg, kit.executor, kit.skillServices, kit.env, PermissionGate { true }, ConfirmationGate { _, _ -> true }, {})
        val r = engine.run("writing_editor", jsonOf("text" to "hola"), ctx(kit))
        assertEquals(SkillStatus.FAILED, r.status)
        assertEquals("security_violation", r.errorCode)
        assertTrue(kit.device.actions.isEmpty())
    }

    @Test fun externalRiskToolsNeedConfirmationUnlessTheUserTappedTheButton() = runBlocking {
        val kit = TestKit(confirm = false)
        val viaModel = kit.executor.execute(com.example.jarvis.core.tools.ToolCall("1", "share_text", """{"text":"hola"}"""), ctx(kit, RequestSource.VOICE))
        assertEquals("not_confirmed", viaModel.errorCode)
        val viaSkill = kit.executor.execute(com.example.jarvis.core.tools.ToolCall("2", "share_text", """{"text":"hola"}"""), ctx(kit, RequestSource.BUTTON).forSkill("message_drafter"))
        assertEquals("not_confirmed", viaSkill.errorCode)
        assertTrue(kit.device.actions.isEmpty())
        val tapped = kit.executor.execute(com.example.jarvis.core.tools.ToolCall("3", "share_text", """{"text":"hola"}"""), ctx(kit, RequestSource.BUTTON))
        assertTrue(tapped.ok)
        assertEquals(RiskLevel.EXTERNAL, kit.registry.definition("compose_email")!!.risk)
        assertFalse("las herramientas internas no se ofrecen al modelo", kit.registry.definitionsFor(voice = true).any { it.name == "compose_email" })
    }

    @Test fun unknownAndNotFoundSkillsAreReportedNotGuessed() = runBlocking {
        val kit = TestKit()
        val r = kit.engine.run("writting_editor", JsonObject(emptyMap()), ctx(kit))
        assertEquals(SkillStatus.NOT_FOUND, r.status)
        assertTrue(r.summary.contains("writing_editor"))
    }

    @Test fun briefCatalogIsCompactAndDistinguishesConfiguration() {
        val kit = TestKit()
        kit.serverConfigured = false
        val brief = kit.skills.briefCatalog(kit.env, "corrige la ortografía de este correo")
        assertTrue(brief.contains("writing_editor:"))
        assertFalse("sin esquemas en el catálogo breve", brief.contains("inputSchema") || brief.contains("\"type\""))
        assertTrue("catálogo breve demasiado largo (${brief.length})", brief.length < 2600)
    }
}
