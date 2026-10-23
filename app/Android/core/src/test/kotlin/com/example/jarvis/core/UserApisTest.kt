package com.example.jarvis.core

import com.example.jarvis.core.business.Alternative
import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.core.business.FieldValue
import com.example.jarvis.core.business.LatLon
import com.example.jarvis.core.business.RestrictedSources
import com.example.jarvis.core.business.SaveProspectResult
import com.example.jarvis.core.business.SourceInfo
import com.example.jarvis.core.business.withoutRestrictedSources
import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.skills.SkillStatus
import com.example.jarvis.core.tools.SchemaValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Integraciones con las claves del usuario: UN Comtrade y Google Maps (datos restringidos). */
class UserApisTest {
    private val trade = """{"reporter":"Costa Rica","product":"Coffee","flowLabel":"Export","totals":[{"year":2024,"valueUsd":350000000,"basis":"total mundial declarado"},{"year":2023,"valueUsd":320000000}],
        "topPartners":[{"partner":"USA","partnerCode":842,"valueUsd":150000000},{"partner":"Belgium","valueUsd":60000000}],"records":6,"warnings":[],
        "source":{"name":"UN Comtrade","url":"https://comtradeplus.un.org"},"retrievedAt":"2025-09-29T13:42:00Z"}"""

    @Test fun schemaPatternIsEnforced() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"hs":{"type":"string","pattern":"^([0-9]{2}|[0-9]{4}|[0-9]{6})$"}}}""").jsonObject
        assertTrue(SchemaValidator.validate(schema, jsonOf("hs" to "0901")).isEmpty())
        assertTrue(SchemaValidator.validate(schema, jsonOf("hs" to "080430")).isEmpty())
        assertEquals(1, SchemaValidator.validate(schema, jsonOf("hs" to "09-01")).size)
        assertEquals(1, SchemaValidator.validate(schema, jsonOf("hs" to "09011")).size)
    }

    @Test fun importExportUsesOfficialTradeStatsWithSuggestedHsCode() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/trade/stats", trade)
            val kit = TestKit(backendUrl = backend.url)
            val r = kit.runSkill("import_export_research", jsonOf("product" to "café de altura", "direction" to "export", "aspects" to listOf("trade_stats")))
            assertEquals(r.summary, SkillStatus.COMPLETED, r.status)
            assertEquals("0901", r.data.str("hs_code"))
            assertEquals("false", r.data["hs_code_confirmed"]!!.jsonPrimitive.content)
            val stats = r.data.obj("sections")!!.arr("trade_stats")!!.map { it.jsonObject }
            assertEquals(listOf("2024", "2023"), stats.filter { it.str("kind") == "total" }.map { it["year"]!!.jsonPrimitive.content })
            assertEquals("USA", stats.first { it.str("kind") == "socio" }.str("partner"))
            assertTrue(r.warnings.any { it.contains("sugerida") })
            assertTrue(r.sources.any { it.title.startsWith("UN Comtrade") && it.kind == "estadística oficial" })
            val sent = backend.requests.single { it.first == "/api/trade/stats" }.second
            assertTrue(sent.contains("\"hsCode\":\"0901\"") && sent.contains("\"flow\":\"export\""))
        } finally { backend.close() }
    }

    @Test fun tradeStatsWithoutServerKeyIsReportedNotInvented() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/trade/stats", """{"error":{"code":"not_configured","message":"La función \"estadísticas de comercio exterior (UN_COMTRADE_API_KEY)\" no está configurada en el servidor."}}""", 503)
            val kit = TestKit(backendUrl = backend.url)
            val r = kit.runSkill("import_export_research", jsonOf("product" to "piña", "hs_code" to "080430", "direction" to "export", "aspects" to listOf("trade_stats")))
            assertEquals(SkillStatus.COMPLETED, r.status)
            assertNull(r.data.obj("sections")!!["trade_stats"])
            assertTrue(r.warnings.any { it.contains("UN_COMTRADE_API_KEY") })
            assertFalse("con código dado no se dice «sugerida»", r.warnings.any { it.contains("sugerida") })
            // Código SA con formato inválido: el motor lo rechaza antes de ejecutar
            assertEquals(SkillStatus.INVALID_INPUT, kit.runSkill("import_export_research", jsonOf("product" to "piña", "hs_code" to "08-04")).status)
        } finally { backend.close() }
    }

    private fun verifiedWithGoogle() = Business(
        id = "osm:node:7",
        name = FieldValue("Ferretería El Sol", FieldStatus.FOUND, listOf("osm")),
        phone = FieldValue("+506 2260-1111", FieldStatus.FOUND, listOf(RestrictedSources.GOOGLE)),
        website = FieldValue("https://ferreelsol.example/", FieldStatus.VERIFIED, listOf("osm", RestrictedSources.GOOGLE)),
        email = FieldValue("ventas@ferreelsol.example", FieldStatus.CONFLICT, listOf("osm"), alternatives = listOf(Alternative("otro@ferreelsol.example", RestrictedSources.GOOGLE))),
        coordinates = FieldValue(LatLon(10.0, -84.11), FieldStatus.FOUND, listOf("osm")),
        sources = mapOf("osm" to SourceInfo("OpenStreetMap", license = "ODbL"), RestrictedSources.GOOGLE to SourceInfo("Google Maps")),
    )

    @Test fun googleDataIsStrippedForMapAndStorage() = runBlocking {
        val clean = verifiedWithGoogle().withoutRestrictedSources()
        assertNull("teléfono solo de Google: no se muestra ni se guarda", clean.phone.value)
        assertEquals(FieldStatus.UNAVAILABLE, clean.phone.status)
        assertTrue(clean.phone.note!!.contains("Google Maps"))
        assertEquals(FieldStatus.FOUND, clean.website.status)
        assertEquals(listOf("osm"), clean.website.sources)
        assertEquals(FieldStatus.FOUND, clean.email.status)
        assertTrue(clean.email.alternatives.isEmpty())
        assertEquals(setOf("osm"), clean.sources.keys)
        assertEquals(LatLon(10.0, -84.11), clean.mapPoint)

        val kit = TestKit()
        val saved = kit.prospects.save(verifiedWithGoogle()) as SaveProspectResult.Saved
        assertNull(saved.prospect.phone)
        assertEquals("https://ferreelsol.example/", saved.prospect.website)
        assertFalse(saved.prospect.snapshotJson.contains("2260-1111"))
        assertFalse(saved.prospect.snapshotJson.contains("Google Maps\""))
        // Sin datos de Google, la copia es la misma
        val osmOnly = verifiedWithGoogle().copy(sources = mapOf("osm" to SourceInfo("OpenStreetMap")), phone = FieldValue())
        assertTrue(osmOnly.withoutRestrictedSources() === osmOnly)
    }
}
