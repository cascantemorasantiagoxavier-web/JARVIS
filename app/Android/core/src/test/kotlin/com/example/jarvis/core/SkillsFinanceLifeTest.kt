package com.example.jarvis.core

import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.CategoryInfo
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.core.business.FieldValue
import com.example.jarvis.core.business.ProspectStatus
import com.example.jarvis.core.business.SaveProspectResult
import com.example.jarvis.core.device.CalendarEvent
import com.example.jarvis.core.device.MediaFile
import com.example.jarvis.core.device.MediaResult
import com.example.jarvis.core.device.PixelDecoder
import com.example.jarvis.core.device.Pixels
import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.net.Product
import com.example.jarvis.core.net.ProductLookup
import com.example.jarvis.core.net.ProductLookupResult
import com.example.jarvis.core.skills.SkillStatus
import com.example.jarvis.core.skills.impl.Sm2
import com.example.jarvis.core.weather.WeatherClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SkillsFinanceLifeTest {
    private fun JsonObject.num(k: String) = this[k]!!.jsonPrimitive.double
    private fun JsonObject.txt(k: String) = this[k]?.jsonPrimitive?.content
    private fun photo() = MediaResult.Ok(MediaFile("foto.jpg", "image/jpeg", ByteArray(2000) { (it % 251).toByte() }))

    // ---------------- finanzas

    @Test fun expenseCaptureParsesSpanishAndNeverGuessesCurrency() = runBlocking {
        val kit = TestKit()
        val r = kit.skillRouter.run(kit.skillRouter.route("Gasté 5 000 colones en gasolina ayer")!!, skillCtx(kit))
        assertEquals(SkillStatus.COMPLETED, r.status)
        val t = r.data.obj("transaction")!!
        assertEquals(5000.0, t.num("amount"), 0.0)
        assertEquals("CRC", t.txt("currency"))
        assertEquals("transporte", t.txt("category"))
        assertEquals("2025-09-28", t.txt("date"))
        val inc = kit.runSkill("expense_capture", jsonOf("action" to "add_from_text", "text" to "me pagaron 150 mil colones por una venta"))
        assertEquals("income", inc.data.obj("transaction")!!.txt("type"))
        assertEquals(150000.0, inc.data.obj("transaction")!!.num("amount"), 0.0)
        assertEquals(SkillStatus.INVALID_INPUT, kit.runSkill("expense_capture", jsonOf("action" to "add_from_text", "text" to "gasté 5000 en gasolina")).status)
        assertEquals("USD", kit.runSkill("expense_capture", jsonOf("action" to "add_from_text", "text" to "pagué 20 dólares de almuerzo")).data.obj("transaction")!!.txt("currency"))
    }

    @Test fun expenseCaptureWarnsOnBudgetAndReceiptNeedsConfirmation() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/vision/analyze", """{"task":"receipt","text":"Recibo","fields":{"total":"₡12 500","currency":"CRC","merchant":"Farmacia Sucre","date":"2025-09-29"},"model":"gpt-test"}""")
            val kit = TestKit(backendUrl = backend.url)
            kit.runSkill("personal_finance", jsonOf("action" to "set_budget", "category" to "salud", "limit" to 15000, "currency" to "CRC"))
            kit.media.photo = photo()
            kit.confirm = false
            assertEquals(SkillStatus.NOT_CONFIRMED, kit.runSkill("expense_capture", jsonOf("action" to "from_receipt")).status)
            assertEquals(0, kit.store.list("finance.transactions").size)
            kit.confirm = true
            val r = kit.runSkill("expense_capture", jsonOf("action" to "from_receipt"))
            assertEquals(SkillStatus.COMPLETED, r.status)
            assertEquals("receipt", r.data.obj("transaction")!!.txt("source"))
            assertEquals("salud", r.data.obj("transaction")!!.txt("category"))
            assertTrue("aviso de presupuesto al superar el 80 %", r.warnings.any { it.contains("83 %") })
            assertTrue(backend.requests.any { it.first == "/api/vision/analyze" && it.second.contains("\"task\":\"receipt\"") })
        } finally { backend.close() }
    }

    @Test fun personalFinanceSummarizesPerCurrencyAndComparesMonths() = runBlocking {
        val kit = TestKit()
        for (text in listOf("gasté 10000 colones en el supermercado", "gasté 5000 colones en gasolina", "recibí 300000 colones de salario", "pagué 15 dólares de netflix")) kit.runSkill("expense_capture", jsonOf("action" to "add_from_text", "text" to text))
        kit.runSkill("expense_capture", jsonOf("action" to "add", "amount" to 4000, "currency" to "CRC", "category" to "comida", "description" to "Almuerzo", "date" to "2025-08-15"))
        val s = kit.runSkill("personal_finance", jsonOf("action" to "summary"))
        val crc = s.data.arr("currencies")!!.map { it.jsonObject }.first { it.txt("currency") == "CRC" }
        assertEquals(300000.0, crc.num("income"), 0.0)
        assertEquals(15000.0, crc.num("expenses"), 0.0)
        assertEquals(285000.0, crc.num("balance"), 0.0)
        assertTrue(s.data.arr("currencies")!!.any { it.jsonObject.txt("currency") == "USD" })
        val cmp = kit.runSkill("personal_finance", jsonOf("action" to "compare_months"))
        val comida = cmp.data.arr("rows")!!.map { it.jsonObject }.first { it.txt("category") == "comida" }
        assertEquals(150, comida.txt("change_percent")!!.toInt())
    }

    @Test fun cashflowForecastFlagsNegativeMonths() = runBlocking {
        val kit = TestKit()
        kit.runSkill("cashflow_forecast", jsonOf("action" to "add_recurring", "name" to "Salario", "type" to "income", "amount" to 400000, "day_of_month" to 30))
        kit.runSkill("cashflow_forecast", jsonOf("action" to "add_recurring", "name" to "Alquiler", "type" to "expense", "amount" to 350000, "day_of_month" to 1))
        kit.runSkill("cashflow_forecast", jsonOf("action" to "add_recurring", "name" to "Préstamo", "type" to "expense", "amount" to 120000, "day_of_month" to 5))
        val f = kit.runSkill("cashflow_forecast", jsonOf("action" to "forecast", "months" to 3, "starting_balance" to 100000))
        val months = f.data.arr("months")!!.map { it.jsonObject }
        assertEquals(listOf(30000.0, -40000.0, -110000.0), months.map { it.num("ending_balance") })
        assertEquals("2025-10", f.data.txt("first_negative_month"))
        val estimated = kit.runSkill("cashflow_forecast", jsonOf("action" to "forecast", "months" to 1))
        assertTrue(estimated.warnings.any { it.contains("Saldo inicial estimado") })
    }

    @Test fun quotesUseExactArithmeticAndNeverDuplicatePayments() = runBlocking {
        val kit = TestKit()
        val c = kit.runSkill("invoice_quote_creator", jsonOf("action" to "create", "client" to "Café <La Montaña>", "client_email" to "compras@montana.cr",
            "lines" to listOf(mapOf("description" to "Saco de café 46 kg", "quantity" to 10, "unit_price" to 45000), mapOf("description" to "Flete", "quantity" to 1, "unit_price" to 0.1))))
        val q = c.data.obj("quote")!!
        assertEquals("COT-2025-0001", q.txt("number"))
        assertEquals(450000.10, q.num("subtotal"), 0.0)
        assertEquals(58500.01, q.num("tax"), 0.0)
        assertEquals(508500.11, q.num("total"), 0.0)
        val html = kit.device.documents.getValue("COT-2025-0001.html")
        assertFalse(html.contains("<La Montaña>"))
        assertTrue(html.contains("No es un comprobante electrónico"))
        assertEquals("COT-2025-0002", kit.runSkill("invoice_quote_creator", jsonOf("action" to "create", "client" to "Otro", "lines" to listOf(mapOf("description" to "x", "quantity" to 1, "unit_price" to 1)), "save" to false)).data.obj("quote")!!.txt("number"))
        assertEquals(SkillStatus.COMPLETED, kit.runSkill("invoice_quote_creator", jsonOf("action" to "update_status", "quote_id" to "1", "status" to "paid")).status)
        assertEquals("already_paid", kit.runSkill("invoice_quote_creator", jsonOf("action" to "record_payment", "quote_id" to "1")).errorCode)
        val income = kit.store.list("finance.transactions")
        assertEquals(1, income.size)
        assertTrue(income.single().json.contains("508500.11"))
        kit.confirm = false
        assertEquals(SkillStatus.NOT_CONFIRMED, kit.runSkill("invoice_quote_creator", jsonOf("action" to "send", "quote_id" to "2", "client_email" to "a@b.cr")).status)
        assertNull(kit.device.lastEmail)
    }

    // ---------------- negocio

    @Test fun crmLogsInteractionsSchedulesFollowUpsAndFindsStaleContacts() = runBlocking {
        val kit = TestKit()
        val p = (kit.prospects.save(Business(id = "osm:node:9", name = FieldValue("Ferretería El Clavo", FieldStatus.FOUND, listOf("osm")))) as SaveProspectResult.Saved).prospect
        val log = kit.runSkill("customer_relationships", jsonOf("action" to "log_interaction", "contact_name" to "ferretería el clavo", "kind" to "llamada", "summary" to "Interesado en tornillería", "follow_up" to "Enviar lista de precios", "follow_up_when" to "mañana a las 9"))
        assertEquals(SkillStatus.COMPLETED, log.status)
        assertEquals(ProspectStatus.CONTACTADO, kit.prospects.list().single().status)
        assertEquals("Seguimiento: Ferretería El Clavo", kit.taskRepo.items.values.single().title)
        val free = kit.runSkill("customer_relationships", jsonOf("action" to "log_interaction", "contact_name" to "Doña Marta", "summary" to "Pidió catálogo"))
        assertTrue(free.summary.contains("contacto libre"))
        assertEquals(1, kit.runSkill("customer_relationships", jsonOf("action" to "history", "prospect_id" to p.id)).data.arr("interactions")!!.size)
        kit.time.advanceSeconds(20L * 24 * 3600)
        val fu = kit.runSkill("customer_relationships", jsonOf("action" to "follow_ups", "days" to 14))
        assertEquals("Ferretería El Clavo", fu.data.arr("stale")!!.single().jsonObject.txt("name"))
        assertEquals(1, fu.data.arr("pending")!!.size)
    }

    @Test fun negotiationCoachPlansAndEvaluatesOffers() = runBlocking {
        val kit = TestKit()
        val plan = kit.runSkill("negotiation_coach", jsonOf("action" to "plan", "role" to "seller", "target" to 50000, "walk_away" to 42000, "their_limit" to 40000))
        assertEquals(56000.0, plan.data.num("opening"), 0.0)
        assertEquals(listOf(53000.0, 51200.0, 50000.0), plan.data.arr("concessions")!!.map { it.jsonPrimitive.double })
        assertTrue(plan.warnings.any { it.contains("no hay zona de acuerdo") })
        assertEquals(SkillStatus.INVALID_INPUT, kit.runSkill("negotiation_coach", jsonOf("action" to "plan", "role" to "seller", "target" to 40000, "walk_away" to 45000)).status)
        val eval = kit.runSkill("negotiation_coach", jsonOf("action" to "evaluate_offer", "role" to "seller", "offer" to 44000, "target" to 50000, "walk_away" to 42000))
        assertEquals("contraofertar", eval.data.txt("decision"))
        assertEquals(47000.0, eval.data.num("counter_offer"), 0.0)
        assertEquals("rechazar", kit.runSkill("negotiation_coach", jsonOf("action" to "evaluate_offer", "role" to "buyer", "offer" to 60000, "target" to 45000, "walk_away" to 55000)).data.txt("decision"))
    }

    @Test fun contractReviewFlagsRisksLocallyWithoutSendingTheContract() = runBlocking {
        val kit = TestKit()
        val llm = FakeSkillLlm(); kit.serverConfigured = true; kit.skillLlm = llm
        val text = "El presente contrato tendrá una duración de un año y se renovará automáticamente por periodos iguales. " +
            "En caso de terminación anticipada, el arrendatario pagará una multa de dos meses de renta. El arrendador podrá modificar unilateralmente el precio. " +
            "El monto mensual es de ₡350 000 pagaderos el 1 de cada mes, con un interés moratorio del 5% mensual. Las partes se someten a los tribunales de Miami, Florida. Firmado el 15 de enero de 2025."
        val r = kit.runSkill("contract_review", jsonOf("source" to "text", "text" to text))
        assertEquals(SkillStatus.COMPLETED, r.status)
        val risks = r.data.arr("risks")!!.map { it.jsonObject.txt("message")!! }
        assertTrue(risks.any { it.startsWith("Renovación automática") })
        assertTrue(risks.any { it.startsWith("Penalidad") })
        assertTrue(risks.any { it.startsWith("Facultad unilateral") })
        assertTrue(risks.any { it.startsWith("Jurisdicción fuera de Costa Rica") })
        assertTrue(risks.any { it.startsWith("Intereses de 5") })
        assertTrue(r.data.arr("amounts")!!.any { it.jsonPrimitive.content == "₡350 000" })
        assertTrue(r.data.arr("dates")!!.any { it.jsonPrimitive.content == "15 de enero de 2025" })
        assertTrue("sin use_ai no sale del teléfono", llm.calls.isEmpty())
        assertTrue(r.warnings.any { it.contains("no sustituye") })
        kit.runSkill("contract_review", jsonOf("source" to "text", "text" to text, "use_ai" to true))
        assertEquals(1, llm.calls.size)
    }

    @Test fun competitorIntelligenceSummarizesMarketFromOsm() = runBlocking {
        val backend = FakeBackend()
        try {
            fun b(i: Int, web: String?, phone: String?, canton: String) = Business(id = "osm:node:$i", name = FieldValue("Panadería $i", FieldStatus.FOUND), website = FieldValue(web, if (web != null) FieldStatus.FOUND else FieldStatus.UNAVAILABLE),
                phone = FieldValue(phone, if (phone != null) FieldStatus.FOUND else FieldStatus.UNAVAILABLE), canton = FieldValue(canton, FieldStatus.FOUND))
            val res = BusinessSearchResult(category = CategoryInfo("bakery", "Panaderías"), total = 5, results = listOf(b(1, null, "2222", "Montes de Oca"), b(2, null, null, "Montes de Oca"), b(3, null, "3333", "Goicoechea"), b(4, null, null, "Montes de Oca"), b(5, null, null, "Curridabat")), retrievedAt = "2025-09-29T13:42:00Z")
            backend.json("/api/business/search", JarvisJson.encodeToString(BusinessSearchResult.serializer(), res))
            val kit = TestKit(backendUrl = backend.url)
            val r = kit.runSkill("competitor_intelligence", jsonOf("category" to "panaderías", "location" to "San Pedro", "own_business_name" to "Panadería 5"))
            assertEquals(4, r.data.num("competitors").toInt())
            assertEquals(0, r.data.obj("with_data_percent")!!.txt("website")!!.toInt())
            assertTrue(r.data.arr("insights")!!.any { it.jsonPrimitive.content.contains("Solo 0 % tiene sitio web") })
            assertEquals("Montes de Oca", r.data.arr("by_zone")!!.first().jsonObject.txt("zone"))
        } finally { backend.close() }
    }

    @Test fun supplierVerifierSignalsWorkOfflineAndOnline() = runBlocking {
        val offline = TestKit().runSkill("supplier_verifier", jsonOf("name" to "Shenzhen LED Co", "email" to "ventas.led@gmail.com", "phone" to "+86 755 1234 5678", "country" to "Costa Rica", "tax_id" to "12345"))
        assertEquals(SkillStatus.PARTIAL, offline.status)
        val msgs = offline.data.arr("signals")!!.map { it.jsonObject.txt("message")!! }
        assertTrue(msgs.any { it.contains("correo gratuito") })
        assertTrue(msgs.any { it.contains("no coincide con el país") })
        assertTrue(msgs.any { it.contains("cédula jurídica") })
        assertTrue(offline.data.num("score") < 45)

        val backend = FakeBackend()
        try {
            backend.json("/api/web/fetch", """{"url":"https://ledco.cn","title":"LED Co","text":"Shenzhen LED Co fabrica bombillos desde 2005.","contacts":{"emails":["sales@ledco.cn"],"phones":["+86 755 1234 5678"]},"retrievedAt":"2025-09-29T13:42:00Z"}""")
            backend.json("/api/web/search", """{"query":"x","retrievedAt":"2025-09-29T13:42:00Z","results":[{"title":"Shenzhen LED Co reviews","url":"https://rev.example/led","snippet":"Buen proveedor, entregas a tiempo.","retrievedAt":"2025-09-29T13:42:00Z"}]}""")
            val r = TestKit(backendUrl = backend.url).runSkill("supplier_verifier", jsonOf("name" to "Shenzhen LED Co", "website" to "https://ledco.cn", "email" to "sales@ledco.cn", "phone" to "+86 755 1234 5678", "country" to "China"))
            assertEquals(SkillStatus.COMPLETED, r.status)
            assertTrue(r.data.num("score") >= 70)
            assertTrue(r.data.arr("signals")!!.any { it.jsonObject.txt("message")!!.contains("aparece en su sitio") })
        } finally { backend.close() }
    }

    // ---------------- planificación

    @Test fun scheduleOptimizerPlacesTasksAroundEventsWithoutOverlaps() = runBlocking {
        val kit = TestKit()
        kit.device.events = listOf(CalendarEvent("Reunión", LocalDateTime.of(2025, 9, 30, 10, 0), LocalDateTime.of(2025, 9, 30, 11, 0), false, null))
        kit.tasks.create("Llamada con banco", dueText = "mañana a las 2 pm")
        kit.tasks.create("Informe mensual (2 h)", dueText = "mañana", priority = com.example.jarvis.core.tasks.TaskPriority.HIGH)
        kit.tasks.create("Revisar correos 30 min", dueText = "mañana")
        kit.tasks.create("Pagar luz", dueText = "2025-09-20")
        val r = kit.runSkill("schedule_optimizer", jsonOf("date" to "tomorrow", "work_start" to "08:00", "work_end" to "17:00"))
        assertEquals(SkillStatus.COMPLETED, r.status)
        val blocks = r.data.arr("blocks")!!.map { it.jsonObject }
        val times = blocks.map { java.time.LocalTime.parse(it.txt("start")) to java.time.LocalTime.parse(it.txt("end")) }
        times.zipWithNext().forEach { (a, b) -> assertFalse("bloques solapados $a $b", b.first < a.second) }
        assertEquals("Pagar luz", blocks.first().txt("title"))
        assertEquals("08:00", blocks.first().txt("start"))
        assertTrue(blocks.any { it.txt("title") == "Informe mensual (2 h)" && java.time.Duration.between(java.time.LocalTime.parse(it.txt("start")), java.time.LocalTime.parse(it.txt("end"))).toMinutes() == 120L })
        assertTrue(blocks.any { it.txt("type") == "tarea fija" && it.txt("start") == "14:00" })
        assertEquals(0, r.data.arr("unscheduled")!!.size)
    }

    @Test fun renewalMonitorSchedulesRemindersAndProtectsDeletion() = runBlocking {
        val kit = TestKit()
        val m = kit.runSkill("renewal_monitor", jsonOf("action" to "add", "name" to "Marchamo", "kind" to "vehículo"))
        assertEquals("2025-12-31", m.data.obj("renewal")!!.txt("expires_on"))
        assertTrue(m.warnings.any { it.contains("31 de diciembre") })
        assertEquals(LocalDateTime.of(2025, 12, 16, 9, 0), kit.scheduler.scheduled.values.single())
        val soon = kit.runSkill("renewal_monitor", jsonOf("action" to "add", "name" to "Licencia", "expires_on" to "2025-10-05", "remind_days_before" to 15))
        assertTrue(soon.warnings.any { it.contains("mañana") })
        assertEquals(1, kit.runSkill("renewal_monitor", jsonOf("action" to "upcoming", "days" to 30)).data.arr("renewals")!!.size)
        kit.confirm = false
        assertEquals(SkillStatus.NOT_CONFIRMED, kit.runSkill("renewal_monitor", jsonOf("action" to "delete", "renewal_id" to "1")).status)
        assertEquals(2, kit.store.list("renewals.items").size)
    }

    @Test fun travelPlannerUsesForecastForPackingAndFlagsAbroadRequirements() = runBlocking {
        val geo = FakeBackend()
        try {
            geo.json("/v1/search", """{"results":[{"name":"Ciudad de Panamá","latitude":8.98,"longitude":-79.52,"admin1":"Panamá","country":"Panamá"}]}""")
            geo.json("/v1/forecast", """{"daily":{"time":["2025-10-01","2025-10-02","2025-10-03"],"weather_code":[61,63,2],"temperature_2m_max":[31.0,30.0,32.0],"temperature_2m_min":[24.0,24.5,25.0],"precipitation_probability_max":[70,85,20]}}""")
            val kit = TestKit(weatherClient = WeatherClient(OkHttpClient(), forecastUrl = geo.url + "v1/forecast", geocodingUrl = geo.url + "v1/search"))
            val r = kit.runSkill("travel_planner", jsonOf("destination" to "Panamá", "start_date" to "2025-10-01", "end_date" to "2025-10-03", "purpose" to "negocios", "budget" to 600, "currency" to "USD", "create_tasks" to true))
            assertEquals(SkillStatus.COMPLETED, r.status)
            assertEquals(3, r.data.arr("itinerary")!!.size)
            val packing = r.data.arr("packing")!!.map { it.jsonPrimitive.content }
            assertTrue(packing.any { it.startsWith("Paraguas") && it.contains("2 de 3") })
            assertTrue(packing.any { it.contains("pasaporte") })
            assertTrue(packing.contains("Ropa formal"))
            assertTrue(r.warnings.any { it.contains("requisitos de entrada") })
            assertEquals(240.0, r.data.arr("budget")!!.first().jsonObject.num("amount"), 0.0)
            assertEquals(3, kit.taskRepo.items.size)
        } finally { geo.close() }
        assertEquals(SkillStatus.INVALID_INPUT, TestKit().runSkill("travel_planner", jsonOf("destination" to "X", "start_date" to "2025-10-05", "end_date" to "2025-10-01")).status)
    }

    @Test fun mealPlannerRespectsPreferencesAndScalesGroceries() = runBlocking {
        val kit = TestKit()
        val plan = kit.runSkill("meal_and_grocery_planner", jsonOf("action" to "plan_week", "people" to 4, "preferences" to listOf("vegetariano")))
        val meals = plan.data.obj("plan")!!.arr("days")!!.flatMap { it.jsonObject.arr("meals")!!.map { m -> m.jsonPrimitive.content } }
        assertEquals(14, meals.size)
        assertFalse(meals.any { it.contains("pollo", true) || it.contains("carne", true) || it.contains("Pescado") || it.contains("atún") })
        val list = kit.runSkill("meal_and_grocery_planner", jsonOf("action" to "grocery_list"))
        val items = list.data.arr("items")!!.map { it.jsonObject.txt("name") to it.jsonObject.txt("quantity") }
        assertTrue(items.isNotEmpty())
        assertFalse(items.any { it.first == "pollo" })
        kit.skillRouter.run(kit.skillRouter.route("agrega leche a la lista del súper")!!, skillCtx(kit))
        kit.runSkill("meal_and_grocery_planner", jsonOf("action" to "check_item", "item" to "leche"))
        val pending = kit.runSkill("meal_and_grocery_planner", jsonOf("action" to "list_groceries"))
        assertTrue(pending.data.arr("items")!!.any { it.jsonObject.txt("name") == "leche" && it.jsonObject.txt("checked") == "true" })
    }

    // ---------------- aprendizaje

    @Test fun sm2SchedulesReviewsLikeSuperMemo() {
        val today = LocalDate.of(2025, 9, 29)
        var c = com.example.jarvis.core.skills.impl.Flashcard("d", "f", "b", due = today.toString())
        c = Sm2.review(c, 4, today); assertEquals(1, c.intervalDays)
        c = Sm2.review(c, 4, today); assertEquals(6, c.intervalDays)
        c = Sm2.review(c, 5, today); assertEquals(15, c.intervalDays)
        c = Sm2.review(c, 1, today); assertEquals(1, c.intervalDays); assertEquals(1, c.lapses)
        assertTrue(c.ease >= 1.3)
    }

    @Test fun personalTutorCreatesStudiesAndQuizzes() = runBlocking {
        val kit = TestKit()
        val c = kit.runSkill("personal_tutor", jsonOf("action" to "create_cards", "deck" to "biología", "text" to "Célula: unidad básica de la vida\nMitocondria: orgánulo que produce energía\nADN: molécula con la información genética\nRibosoma: sintetiza proteínas\nLa fotosíntesis es el proceso por el que las plantas producen glucosa."))
        assertEquals(5, c.data.txt("added")!!.toInt())
        assertEquals(0, kit.runSkill("personal_tutor", jsonOf("action" to "create_cards", "deck" to "biología", "text" to "Célula: unidad básica de la vida")).data.txt("added")!!.toInt())
        val study = kit.runSkill("personal_tutor", jsonOf("action" to "study", "deck" to "biología"))
        val card = study.data.obj("card")!!
        assertFalse("no revela la respuesta al preguntar", study.summary.contains("unidad básica"))
        val ans = kit.runSkill("personal_tutor", jsonOf("action" to "answer", "card_id" to card.txt("id"), "answer" to "la unidad básica de la vida"))
        assertEquals(4, ans.data.obj("card")!!.txt("grade")!!.toInt())
        val quiz = kit.runSkill("personal_tutor", jsonOf("action" to "quiz", "deck" to "biología", "questions" to 3))
        quiz.data.arr("questions")!!.forEach { q -> val o = q.jsonObject; assertEquals(4, o.arr("options")!!.size); assertTrue(o.txt("answer_index")!!.toInt() in 0..3) }
        assertEquals(SkillStatus.PARTIAL, kit.runSkill("personal_tutor", jsonOf("action" to "explain", "topic" to "fotosíntesis")).status)
    }

    @Test fun languageCoachCorrectsCommonEnglishMistakes() = runBlocking {
        val kit = TestKit()
        val r = kit.runSkill("language_coach", jsonOf("action" to "check_sentence", "language" to "en", "sentence" to "i have 30 years and people is nice"))
        val rules = r.data.arr("issues")!!.map { it.jsonObject.txt("rule") }
        assertTrue(rules.containsAll(listOf("pronombre", "edad", "concordancia", "puntuación")))
        assertEquals(SkillStatus.COMPLETED, kit.runSkill("language_coach", jsonOf("action" to "phrasebook", "language" to "pt")).status)
        kit.runSkill("language_coach", jsonOf("action" to "add_words", "language" to "en", "words" to listOf(mapOf("word" to "invoice", "translation" to "factura"))))
        assertEquals("invoice", kit.runSkill("language_coach", jsonOf("action" to "study", "language" to "en")).data.obj("card")!!.txt("front"))
        assertEquals(0, kit.runSkill("personal_tutor", jsonOf("action" to "progress")).data.arr("decks")!!.size)
    }

    // ---------------- medios

    @Test fun voiceMemoInboxClassifiesAndConvertsOnce() = runBlocking {
        val kit = TestKit()
        val cap = kit.skillRouter.run(kit.skillRouter.route("guarda esta idea: tengo que llamar al contador mañana a las 10")!!, skillCtx(kit))
        assertEquals("task", cap.data.obj("item")!!.txt("suggestion"))
        val conv = kit.runSkill("voice_memo_inbox", jsonOf("action" to "convert", "item_id" to "1"))
        assertEquals(SkillStatus.COMPLETED, conv.status)
        val task = kit.taskRepo.items.values.single()
        assertEquals("Llamar al contador mañana a las 10", task.title)
        assertEquals(LocalDateTime.of(2025, 9, 30, 10, 0), task.due)
        assertEquals("already_processed", kit.runSkill("voice_memo_inbox", jsonOf("action" to "convert", "item_id" to "1")).errorCode)
        kit.runSkill("voice_memo_inbox", jsonOf("action" to "capture_text", "text" to "Gasté 3500 colones en un café"))
        kit.runSkill("voice_memo_inbox", jsonOf("action" to "convert", "item_id" to "2"))
        assertEquals(1, kit.store.list("finance.transactions").size)
    }

    @Test fun voiceMemoRecordingIsTranscribedByServer() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/audio/transcribe", """{"text":"Recuérdame comprar café el viernes","language":"es","model":"gpt-4o-mini-transcribe"}""")
            val kit = TestKit(backendUrl = backend.url)
            kit.media.audio = MediaResult.Ok(MediaFile("nota.m4a", "audio/mp4", ByteArray(4000) { 1 }))
            val r = kit.runSkill("voice_memo_inbox", jsonOf("action" to "record"))
            assertEquals(SkillStatus.COMPLETED, r.status)
            assertEquals("task", r.data.obj("item")!!.txt("suggestion"))
        } finally { backend.close() }
        assertEquals(SkillStatus.NEEDS_CONFIGURATION, TestKit().runSkill("voice_memo_inbox", jsonOf("action" to "record")).status)
    }

    @Test fun visualSceneAssistantDescribesUserPhotoThroughServer() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/vision/analyze", """{"task":"describe","text":"Una taza de café sobre una mesa de madera.","model":"gpt-test"}""")
            val kit = TestKit(backendUrl = backend.url)
            assertEquals(SkillStatus.CANCELLED, kit.runSkill("visual_scene_assistant", jsonOf("source" to "camera")).status)
            kit.media.photo = photo()
            val r = kit.runSkill("visual_scene_assistant", jsonOf("source" to "camera", "task" to "describe"))
            assertEquals("Una taza de café sobre una mesa de madera.", r.data.txt("text"))
            assertTrue(r.warnings.any { it.contains("no se guarda") })
        } finally { backend.close() }
    }

    private fun barcodePixels(text: String, format: BarcodeFormat, w: Int = 400, h: Int = 200): Pixels {
        val m = MultiFormatWriter().encode(text, format, w, h)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Pixels(m.width, m.height, px)
    }

    @Test fun scannerReadsBarcodeOnDeviceLooksUpProductAndAddsToInventory() = runBlocking {
        val kit = TestKit()
        kit.media.photo = photo()
        kit.pixelDecoder = PixelDecoder { barcodePixels("7501055363056", BarcodeFormat.EAN_13) }
        kit.productLookup = ProductLookup { code -> ProductLookupResult.Found(Product(code, "Café molido", "Marca X", "500 g", null, "https://world.openfoodfacts.org/product/$code")) }
        val r = kit.runSkill("barcode_business_card_scanner", jsonOf("mode" to "barcode", "add_to_inventory" to true, "quantity" to 6))
        assertEquals(r.summary, SkillStatus.COMPLETED, r.status)
        assertEquals("7501055363056", r.data.txt("code"))
        assertEquals("EAN_13", r.data.txt("format"))
        assertEquals("Café molido", r.data.obj("product")!!.txt("name"))
        kit.media.photo = photo()
        val again = kit.runSkill("barcode_business_card_scanner", jsonOf("mode" to "barcode", "add_to_inventory" to true, "quantity" to 4))
        assertEquals(10.0, again.data.obj("inventory")!!.num("quantity"), 0.0)
        assertEquals(1, kit.store.list("inventory.items").size)
        kit.pixelDecoder = PixelDecoder { Pixels(10, 10, IntArray(100) { -1 }) }
        kit.media.photo = photo()
        assertEquals("no_barcode", kit.runSkill("barcode_business_card_scanner", jsonOf("mode" to "barcode")).errorCode)
    }

    @Test fun scannerReadsBusinessCardsFromQrOrServerAndConfirmsBeforeSaving() = runBlocking {
        val kit = TestKit()
        kit.media.photo = photo()
        kit.pixelDecoder = PixelDecoder { barcodePixels("BEGIN:VCARD\nVERSION:3.0\nFN:Ana Mora\nORG:Café La Montaña\nTEL:+50688887777\nEMAIL:ana@montana.cr\nEND:VCARD", BarcodeFormat.QR_CODE, 400, 400) }
        kit.confirm = false
        assertEquals(SkillStatus.NOT_CONFIRMED, kit.runSkill("barcode_business_card_scanner", jsonOf("mode" to "business_card", "save_contact" to true)).status)
        assertEquals(0, kit.store.list("crm.contacts").size)
        kit.confirm = true
        kit.media.photo = photo()
        val qr = kit.runSkill("barcode_business_card_scanner", jsonOf("mode" to "business_card", "save_contact" to true))
        assertEquals("Ana Mora", qr.data.obj("contact")!!.txt("name"))
        assertEquals("true", qr.data.txt("verified"))
        assertEquals(1, kit.store.list("crm.contacts").size)

        val backend = FakeBackend()
        try {
            backend.json("/api/vision/analyze", """{"task":"business_card","text":"","fields":{"name":"Luis Rojas","company":"Ferretería Sol","phone":"2222-3333","email":"luis@ferresol"},"model":"gpt-test"}""")
            val k2 = TestKit(backendUrl = backend.url)
            k2.media.photo = photo()
            val ai = k2.runSkill("barcode_business_card_scanner", jsonOf("mode" to "business_card"))
            assertEquals("false", ai.data.txt("verified"))
            assertTrue(ai.warnings.any { it.contains("verifícalos") })
            assertTrue(ai.warnings.any { it.contains("no parece válido") })
        } finally { backend.close() }
    }
}
