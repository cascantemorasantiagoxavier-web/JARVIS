package com.example.jarvis.core

import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.tools.ConfirmationGate
import com.example.jarvis.core.tools.PermissionGate
import com.example.jarvis.core.tools.SchemaValidator
import com.example.jarvis.core.tools.ToolCall
import com.example.jarvis.core.tools.ToolCatalog
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.ToolExecutor
import com.example.jarvis.core.tools.ToolHandler
import com.example.jarvis.core.tools.ToolRegistry
import com.example.jarvis.core.tools.ToolResult
import com.example.jarvis.core.tools.ToolSite
import com.example.jarvis.core.tools.WorkingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsInfraTest {
    private val defs = ToolCatalog.loadFromResources()

    @Test fun catalogLoadsAndEveryToolIsImplemented() {
        assertTrue(defs.size >= 30)
        val kit = TestKit()
        kit.registry.verifyComplete() // ninguna herramienta decorativa sin implementación
        assertEquals(ToolSite.BACKEND, defs.first { it.name == "business_search" }.site)
        assertTrue(defs.first { it.name == "forget" }.requiresConfirmation)
        assertTrue(defs.first { it.name == "delete_task" }.requiresConfirmation)
        assertTrue(defs.first { it.name == "end_conversation" }.voiceOnly)
        assertFalse(kit.registry.definitionsFor(voice = false).any { it.name == "end_conversation" })
        assertTrue(kit.registry.definitionsFor(voice = true).any { it.name == "end_conversation" })
    }

    @Test fun catalogRejectsBadNamesAndDuplicates() {
        val bad = """{"version":1,"tools":[{"name":"Bad Name","description":"x","site":"device","parameters":{"type":"object"}}]}"""
        assertTrue(runCatching { ToolCatalog.parse(bad) }.isFailure)
        val dup = """{"version":1,"tools":[{"name":"a_b","description":"x","site":"device","parameters":{"type":"object"}},{"name":"a_b","description":"y","site":"device","parameters":{"type":"object"}}]}"""
        assertTrue(runCatching { ToolCatalog.parse(dup) }.isFailure)
    }

    private fun schemaOf(name: String) = defs.first { it.name == name }.parameters
    private fun obj(s: String) = JarvisJson.parseToJsonElement(s).jsonObject

    @Test fun schemaValidation() {
        assertTrue(SchemaValidator.validate(schemaOf("business_search"), obj("""{"category":"supermercados","location":"San José","limit":20}""")).isEmpty())
        assertTrue(SchemaValidator.validate(schemaOf("business_search"), obj("""{"location":"San José"}""")).any { "category es obligatorio" in it })
        assertTrue(SchemaValidator.validate(schemaOf("business_search"), obj("""{"category":"x","limit":500}""")).any { "≤ 50" in it })
        assertTrue(SchemaValidator.validate(schemaOf("business_search"), obj("""{"category":"super","hack":true}""")).any { "no está permitido" in it })
        assertTrue(SchemaValidator.validate(schemaOf("business_search"), obj("""{"category":"super","limit":"20"}""")).any { "entero" in it })
        assertTrue(SchemaValidator.validate(schemaOf("list_tasks"), obj("""{"filter":"todas"}""")).any { "uno de" in it })
        assertTrue(SchemaValidator.validate(schemaOf("convert_units"), obj("""{"value":1.5,"from":"km","to":"mi"}""")).isEmpty())
        assertTrue(SchemaValidator.validate(schemaOf("get_device_status"), obj("""{"aspects":["battery","root"]}""")).any { "uno de" in it })
    }

    private fun ctx() = ToolContext(RequestSource.TEXT, RecordingUi(), WorkingContext())

    private fun executorWith(
        handler: ToolHandler,
        name: String = "get_datetime",
        perm: Boolean = true,
        confirm: Boolean = true,
        logs: MutableList<String> = mutableListOf(),
    ): ToolExecutor {
        val reg = ToolRegistry(defs)
        reg.register(name, handler)
        return ToolExecutor(reg, PermissionGate { perm }, ConfirmationGate { _, _ -> confirm }, { logs += "${it.tool}:${it.ok}:${it.errorCode}" }, TestKit().time, deviceTimeoutMs = 200)
    }

    @Test fun executorValidatesBeforeRunning() = runTest {
        var ran = false
        val ex = executorWith(ToolHandler { _, _ -> ran = true; ToolResult.ok("ok") }, name = "calculate")
        assertEquals("unknown_tool", ex.execute(ToolCall("1", "rm_rf", "{}"), ctx()).errorCode)
        assertEquals("invalid_arguments", ex.execute(ToolCall("2", "calculate", "{no json"), ctx()).errorCode)
        assertEquals("invalid_arguments", ex.execute(ToolCall("3", "calculate", """{"expression":""}"""), ctx()).errorCode)
        assertEquals("not_implemented", ex.execute(ToolCall("4", "web_search", """{"query":"hola"}"""), ctx()).errorCode)
        assertFalse(ran)
        assertTrue(ex.execute(ToolCall("5", "calculate", """{"expression":"1+1"}"""), ctx()).ok)
        assertTrue(ran)
    }

    @Test fun executorEnforcesPermissionsAndConfirmation() = runTest {
        val logs = mutableListOf<String>()
        val denied = executorWith(ToolHandler { _, _ -> ToolResult.ok("x") }, name = "calendar_events", perm = false, logs = logs)
        assertEquals("permission_denied", denied.execute(ToolCall("1", "calendar_events", "{}"), ctx()).errorCode)
        var deleted = false
        val noConfirm = executorWith(ToolHandler { _, _ -> deleted = true; ToolResult.ok("x") }, name = "forget", confirm = false)
        assertEquals("not_confirmed", noConfirm.execute(ToolCall("2", "forget", """{"memory_id":1}"""), ctx()).errorCode)
        assertFalse("una acción destructiva no se ejecuta sin confirmación", deleted)
        val yes = executorWith(ToolHandler { _, _ -> deleted = true; ToolResult.ok("x") }, name = "forget", confirm = true)
        assertTrue(yes.execute(ToolCall("3", "forget", """{"memory_id":1}"""), ctx()).ok)
        assertTrue(deleted)
        assertEquals(listOf("calendar_events:false:permission_denied"), logs)
    }

    @Test fun executorMapsTimeoutsAndErrors() = runTest {
        val slow = executorWith(ToolHandler { _, _ -> delay(5_000); ToolResult.ok("tarde") })
        assertEquals("timeout", slow.execute(ToolCall("1", "get_datetime", "{}"), ctx()).errorCode)
        val backend = executorWith(ToolHandler { _, _ -> throw BackendException("offline", "Sin conexión con el servidor.") })
        val r = backend.execute(ToolCall("2", "get_datetime", "{}"), ctx())
        assertEquals("offline", r.errorCode)
        assertEquals("Sin conexión con el servidor.", r.message)
        val crash = executorWith(ToolHandler { _, _ -> error("boom") })
        assertEquals("tool_failed", crash.execute(ToolCall("3", "get_datetime", "{}"), ctx()).errorCode)
    }

    @Test fun executorPropagatesCancellation() = runTest {
        val ex = executorWith(ToolHandler { _, _ -> delay(100); ToolResult.ok("x") })
        var cancelled = false
        val job = launch {
            try { ex.execute(ToolCall("1", "get_datetime", "{}"), ctx()) } catch (e: CancellationException) { cancelled = true; throw e }
        }
        delay(10)
        job.cancel()
        job.join()
        assertTrue(cancelled)
    }

    @Test fun modelOutputIsCompactAndBounded() {
        val big = ToolResult.ok("ok", JarvisJson.parseToJsonElement("""{"text":"${"x".repeat(10_000)}"}"""))
        val out = big.toModelOutput(500)
        assertTrue(out.length <= 500)
        assertTrue(out.endsWith("…[recortado]"))
        val err = ToolResult.error("offline", "Sin red").toModelOutput()
        assertEquals("""{"ok":false,"message":"Sin red","error":"offline"}""", err)
        assertEquals(JsonObject(emptyMap()), ToolRegistry.parseArguments(""))
    }
}
