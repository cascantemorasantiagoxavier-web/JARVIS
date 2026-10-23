package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.device.MediaResult
import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.double
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.skills.SkillStatus
import com.example.jarvis.core.text.TextNormalizer
import com.example.jarvis.core.tools.local.NaturalDateParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64

/** Interpretación de gastos e ingresos dichos en lenguaje natural (sin red ni IA). */
object ExpenseParser {
    private val categories = linkedMapOf(
        "comida" to listOf("almuerzo", "desayuno", "cena", "comida", "supermercado", "super", "restaurante", "soda", "cafe", "pizza", "panaderia", "mercado", "verdura"),
        "transporte" to listOf("gasolina", "combustible", "diesel", "uber", "didi", "taxi", "bus", "peaje", "parqueo", "estacionamiento", "tren"),
        "servicios" to listOf("luz", "agua", "internet", "telefono", "celular", "ice", "aya", "kolbi", "cable", "electricidad", "recibo"),
        "vivienda" to listOf("alquiler", "renta", "hipoteca", "condominio", "mantenimiento"),
        "salud" to listOf("farmacia", "medico", "doctor", "clinica", "medicina", "dentista", "examen", "laboratorio"),
        "educación" to listOf("colegio", "escuela", "universidad", "curso", "libro", "matricula", "clases"),
        "entretenimiento" to listOf("cine", "netflix", "spotify", "concierto", "juego", "paseo", "bar", "salida"),
        "impuestos" to listOf("hacienda", "marchamo", "impuesto", "iva", "renta anual", "municipalidad"),
        "negocio" to listOf("proveedor", "inventario", "mercaderia", "materia prima", "envio", "publicidad", "anuncio"),
        "compras" to listOf("ropa", "zapatos", "tienda", "amazon", "regalo", "electrodomestico"),
    )
    private val incomeWords = listOf("recibi", "cobre", "me pagaron", "ingreso", "vendi", "venta de", "salario", "deposito de", "me depositaron", "gane")

    data class Parsed(val type: String, val money: Money, val category: String, val description: String, val date: LocalDate)

    fun category(text: String): String {
        val f = TextNormalizer.fold(text)
        return categories.entries.firstOrNull { (_, keys) -> keys.any { Regex("\\b${Regex.escape(it)}").containsMatchIn(f) } }?.key ?: "otros"
    }

    fun parse(text: String, today: LocalDate, now: java.time.ZonedDateTime): Parsed? {
        val money = Money.parse(text, defaultCurrency = if (SkillText.containsAny(text, "colon", "₡", "mil")) "CRC" else null) ?: return null
        val f = TextNormalizer.fold(text)
        val type = if (incomeWords.any { f.contains(it) }) "income" else "expense"
        val dateWord = Regex("\\b(hoy|ayer|anteayer|antier|el (lunes|martes|miercoles|jueves|viernes|sabado|domingo)|el \\d{1,2} de \\w+)\\b").find(f)?.value
        val date = when (dateWord) {
            null, "hoy" -> today
            "ayer" -> today.minusDays(1)
            "anteayer", "antier" -> today.minusDays(2)
            else -> NaturalDateParser.parse(dateWord, now)?.dateTime?.toLocalDate()?.let { if (it.isAfter(today)) it.minusWeeks(1) else it } ?: today
        }
        val description = text.trim().replace(Regex("(?i)^(jarvis[, ]+)?(anota|registra|apunta)( que)?\\s+"), "").trim().replaceFirstChar { it.uppercase() }
        return Parsed(type, money, if (type == "income") "ingresos" else category(text), SkillText.truncate(description, 200), date)
    }
}

private fun period(today: LocalDate, p: String?): Pair<LocalDate, LocalDate> = when (p) {
    "week" -> today.minusDays(6) to today
    "last_month" -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
    "year" -> today.withDayOfYear(1) to today
    "all" -> LocalDate.of(2000, 1, 1) to today
    else -> today.withDayOfMonth(1) to today
}

private fun inRange(t: Transaction, r: Pair<LocalDate, LocalDate>) = runCatching { LocalDate.parse(t.date) }.getOrNull()?.let { !it.isBefore(r.first) && !it.isAfter(r.second) } ?: false
private fun BigDecimal.money(cur: String) = Money.format(this, cur)
private fun txView(id: String, t: Transaction) = mapOf("id" to id, "type" to t.type, "amount" to BigDecimal(t.amount).toDouble(), "currency" to t.currency, "category" to t.category, "description" to t.description, "date" to t.date, "source" to t.source)

// ============================================================ expense_capture

class ExpenseCaptureSkill : Skill {
    override val id = "expense_capture"

    override fun parseText(text: String): JsonObject? {
        val f = TextNormalizer.canonical(text)
        if (!Regex("^(anota |registra |apunta )?(que )?(gaste|pague|compre|recibi|cobre|me pagaron|vendi)\\b").containsMatchIn(f)) return null
        if (Money.parse(text, "CRC") == null) return null
        return jsonOf("action" to "add_from_text", "text" to text)
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val col = ctx.transactions()
        val today = ctx.time.zonedNow().toLocalDate()
        val src = listOf(SkillSource("Registro financiero local", kind = "local"))
        return when (input.str("action")) {
            "add_from_text" -> {
                val text = input.str("text") ?: return Outcome.invalid("Falta el texto (p. ej. «gasté 5000 colones en gasolina»).")
                val p = ExpenseParser.parse(text, today, ctx.time.zonedNow())
                    ?: return Outcome.invalid("No encontré un monto con moneda en \"$text\". Ejemplo: «gasté ₡5 000 en gasolina» o «20 dólares de almuerzo».")
                save(ctx, Transaction(p.type, p.money.amount.toPlainString(), p.money.currency, input.str("category") ?: p.category, p.description, p.date.toString()), src)
            }
            "add" -> {
                val amount = input.double("amount") ?: return Outcome.invalid("Falta el monto.")
                if (amount <= 0) return Outcome.invalid("El monto debe ser positivo.")
                val date = input.str("date")?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() ?: NaturalDateParser.parse(d, ctx.time.zonedNow())?.dateTime?.toLocalDate() ?: return Outcome.invalid("No entendí la fecha \"$d\".") } ?: today
                val desc = input.str("description") ?: input.str("category") ?: "Movimiento"
                val type = input.str("type") ?: "expense"
                save(ctx, Transaction(type, BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString(), input.str("currency") ?: "CRC", input.str("category") ?: if (type == "income") "ingresos" else ExpenseParser.category(desc), desc, date.toString()), src)
            }
            "from_receipt" -> receipt(ctx, input, src)
            "list" -> {
                val range = period(today, input.str("period"))
                val list = col.all().filter { inRange(it.second, range) }.sortedByDescending { it.second.date }
                val totals = list.groupBy { it.second.currency to it.second.type }.map { (k, l) -> mapOf("currency" to k.first, "type" to k.second, "total" to l.sumOf { BigDecimal(it.second.amount) }.toDouble()) }
                Outcome.done("${list.size} movimientos del ${range.first} al ${range.second}.", jsonOf("action" to "list", "transactions" to list.map { txView(it.first, it.second) }, "totals" to totals), src,
                    cards = listOf(ResultCard.Info("Movimientos", list.take(20).map { (_, t) -> "${t.date} ${if (t.type == "income") "+" else "−"}${BigDecimal(t.amount).money(t.currency)} ${t.category}: ${t.description}" })))
            }
            "delete" -> {
                val id = input.str("transaction_id") ?: return Outcome.invalid("Falta transaction_id.")
                val t = col.get(id) ?: return Outcome.fail("not_found", "No existe el movimiento $id.", "Pide la lista de movimientos.")
                if (!ctx.confirm("Borrar el movimiento ${t.date}: ${BigDecimal(t.amount).money(t.currency)} (${t.description}).")) return Outcome.notConfirmed()
                col.delete(id)
                Outcome.done("Movimiento $id borrado.", jsonOf("action" to "delete", "transaction" to txView(id, t)), src)
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }

    private suspend fun save(ctx: SkillContext, t: Transaction, src: List<SkillSource>): SkillOutcome {
        val col = ctx.transactions()
        val id = col.nextId()
        col.save(id, t)
        val budget = ctx.budgets().values().firstOrNull { it.category == t.category && it.currency == t.currency }
        val warning = budget?.let { b ->
            val month = YearMonth.parse(t.date.take(7))
            val spent = col.values().filter { it.type == "expense" && it.category == t.category && it.currency == t.currency && it.date.startsWith(month.toString()) }.sumOf { BigDecimal(it.amount) }
            val limit = BigDecimal(b.monthlyLimit)
            if (limit.signum() > 0 && spent >= limit.multiply(BigDecimal("0.8"))) "Llevas ${spent.money(t.currency)} de ${limit.money(t.currency)} en ${t.category} este mes (${spent.multiply(BigDecimal(100)).divide(limit, 0, RoundingMode.HALF_UP)} %)." else null
        }
        return Outcome.done("Registré ${if (t.type == "income") "un ingreso" else "un gasto"} de ${BigDecimal(t.amount).money(t.currency)} en ${t.category} (${t.date})." + (warning?.let { " $it" } ?: ""),
            jsonOf("action" to "add", "transaction" to txView(id, t)), src, listOfNotNull(warning))
    }

    private suspend fun receipt(ctx: SkillContext, input: JsonObject, src: List<SkillSource>): SkillOutcome {
        ctx.requireServer("Leer recibos")?.let { return it }
        val photo = when (val r = ctx.services.media.capturePhoto()) {
            is MediaResult.Ok -> r.file
            MediaResult.Cancelled -> return SkillOutcome(SkillStatus.CANCELLED, "No se tomó ninguna foto.", errorCode = "cancelled")
            is MediaResult.Unavailable -> return Outcome.fail("media_unavailable", r.reason, "Abre JARVIS en pantalla y vuelve a intentarlo.")
        }
        val r = ctx.tool("analyze_image", jsonOf("image_base64" to Base64.getEncoder().encodeToString(photo.bytes), "mime_type" to photo.mimeType, "task" to "receipt"))
        if (!r.ok) return Outcome.fromTool(r, "Leer el recibo")
        val fields = (r.data as JsonObject).obj("fields")
        val total = fields?.str("total")?.let { Money.parse(it, fields.str("currency") ?: "CRC") }
            ?: return Outcome.fail("no_total", "No pude leer el total del recibo con seguridad.", "Regístralo por voz: «gasté ₡… en …».")
        val merchant = fields.str("merchant") ?: "Recibo"
        val date = fields.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: ctx.time.zonedNow().toLocalDate()
        val t = Transaction("expense", total.amount.toPlainString(), total.currency, input.str("category") ?: ExpenseParser.category(merchant + " " + (fields.str("items") ?: "")), merchant, date.toString(), source = "receipt")
        // Datos extraídos por IA: se confirman antes de guardar.
        val assumed = if (fields.str("currency") == null) " La moneda no se leyó en el recibo: supuse colones." else ""
        if (!ctx.confirm("Guardar gasto leído del recibo: ${total.format()} en $merchant ($date).$assumed Verifica que coincide con el recibo.")) return Outcome.notConfirmed("No guardé el gasto del recibo.")
        return save(ctx, t, src + SkillSource("Recibo fotografiado (lectura por IA)", kind = "imagen"))
    }
}

// ============================================================ personal_finance

class PersonalFinanceSkill : Skill {
    override val id = "personal_finance"

    override fun parseText(text: String): JsonObject? {
        val f = TextNormalizer.canonical(text)
        return when {
            SkillText.containsAny(f, "cuanto he gastado", "cuanto gaste", "resumen de gastos", "resumen financiero", "como van mis finanzas") -> jsonOf("action" to "summary", "period" to if (f.contains("pasado")) "last_month" else if (f.contains("semana")) "week" else "month")
            SkillText.containsAny(f, "compara mis gastos", "comparado con el mes pasado") -> jsonOf("action" to "compare_months")
            else -> null
        }
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val today = ctx.time.zonedNow().toLocalDate()
        val tx = ctx.transactions().values()
        val src = listOf(SkillSource("Registro financiero local", kind = "local"))
        return when (input.str("action")) {
            "summary" -> {
                val range = period(today, input.str("period"))
                val list = tx.filter { inRange(it, range) }
                if (list.isEmpty()) return Outcome.done("No hay movimientos del ${range.first} al ${range.second}.", jsonOf("action" to "summary", "currencies" to emptyList<Any>()), src)
                val budgets = ctx.budgets().values()
                val perCurrency = list.groupBy { it.currency }.map { (cur, l) ->
                    val inc = l.filter { it.type == "income" }.sumOf { BigDecimal(it.amount) }
                    val exp = l.filter { it.type == "expense" }.sumOf { BigDecimal(it.amount) }
                    val byCat = l.filter { it.type == "expense" }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { BigDecimal(it.amount) } }.entries.sortedByDescending { it.value }
                    mapOf("currency" to cur, "income" to inc.toDouble(), "expenses" to exp.toDouble(), "balance" to (inc - exp).toDouble(),
                        "by_category" to byCat.map { (c, v) ->
                            val b = budgets.firstOrNull { it.category == c && it.currency == cur }
                            mapOf("category" to c, "total" to v.toDouble(), "budget" to b?.monthlyLimit?.toDouble(), "percent_of_budget" to b?.let { bb -> if (BigDecimal(bb.monthlyLimit).signum() > 0) v.multiply(BigDecimal(100)).divide(BigDecimal(bb.monthlyLimit), 0, RoundingMode.HALF_UP).toInt() else null })
                        })
                }
                val alerts = perCurrency.flatMap { c -> (c["by_category"] as List<Map<String, Any?>>).filter { ((it["percent_of_budget"] as Int?) ?: 0) >= 80 }.map { "${it["category"]}: ${it["percent_of_budget"]} % del presupuesto" } }
                val msg = perCurrency.joinToString(" ") { c -> val cur = c["currency"] as String; "En $cur: ingresos ${BigDecimal(c["income"].toString()).money(cur)}, gastos ${BigDecimal(c["expenses"].toString()).money(cur)}, balance ${BigDecimal(c["balance"].toString()).money(cur)}." } +
                    (if (alerts.isNotEmpty()) " Atención: ${alerts.joinToString("; ")}." else "")
                Outcome.done(msg, jsonOf("action" to "summary", "from" to range.first.toString(), "to" to range.second.toString(), "currencies" to perCurrency, "alerts" to alerts), src, alerts,
                    listOf(ResultCard.Info("Finanzas (${range.first} – ${range.second})", perCurrency.flatMap { c -> val cur = c["currency"] as String; listOf("$cur — balance ${BigDecimal(c["balance"].toString()).money(cur)}") + (c["by_category"] as List<Map<String, Any?>>).map { "  ${it["category"]}: ${BigDecimal(it["total"].toString()).money(cur)}" + (it["percent_of_budget"]?.let { p -> " ($p % del presupuesto)" } ?: "") } })))
            }
            "set_budget" -> {
                val cat = input.str("category")?.lowercase() ?: return Outcome.invalid("Falta la categoría.")
                val limit = input.double("limit")?.takeIf { it > 0 } ?: return Outcome.invalid("Falta el límite mensual (positivo).")
                val cur = input.str("currency") ?: "CRC"
                val col = ctx.budgets()
                val existing = col.all().firstOrNull { it.second.category == cat && it.second.currency == cur }
                col.save(existing?.first ?: col.nextId(), Budget(cat, BigDecimal.valueOf(limit).stripTrailingZeros().toPlainString(), cur))
                Outcome.done("Presupuesto de $cat: ${BigDecimal.valueOf(limit).money(cur)} al mes.", jsonOf("action" to "set_budget", "budget" to mapOf("category" to cat, "limit" to limit, "currency" to cur)), src)
            }
            "budgets" -> {
                val month = today.toString().take(7)
                val rows = ctx.budgets().values().map { b ->
                    val spent = tx.filter { it.type == "expense" && it.category == b.category && it.currency == b.currency && it.date.startsWith(month) }.sumOf { BigDecimal(it.amount) }
                    mapOf("category" to b.category, "limit" to BigDecimal(b.monthlyLimit).toDouble(), "spent" to spent.toDouble(), "currency" to b.currency, "remaining" to (BigDecimal(b.monthlyLimit) - spent).toDouble())
                }
                Outcome.done(if (rows.isEmpty()) "No tienes presupuestos. Ejemplo: «presupuesto de comida 150 000 colones»." else rows.joinToString("; ") { "${it["category"]}: quedan ${BigDecimal(it["remaining"].toString()).money(it["currency"] as String)}" } + ".", jsonOf("action" to "budgets", "budgets" to rows), src)
            }
            "top_expenses" -> {
                val range = period(today, input.str("period"))
                val top = tx.filter { it.type == "expense" && inRange(it, range) }.sortedByDescending { BigDecimal(it.amount) }.take(input.int("limit") ?: 5)
                Outcome.done(if (top.isEmpty()) "Sin gastos en el periodo." else "Mayores gastos: " + top.joinToString("; ") { "${BigDecimal(it.amount).money(it.currency)} ${it.description}" } + ".", jsonOf("action" to "top_expenses", "transactions" to top.map { txView("", it) }), src)
            }
            "compare_months" -> {
                val thisM = YearMonth.from(today); val lastM = thisM.minusMonths(1)
                fun byCat(m: YearMonth) = tx.filter { it.type == "expense" && it.date.startsWith(m.toString()) }.groupBy { it.category to it.currency }.mapValues { (_, l) -> l.sumOf { BigDecimal(it.amount) } }
                val a = byCat(thisM); val b = byCat(lastM)
                val rows = (a.keys + b.keys).map { k ->
                    val now = a[k] ?: BigDecimal.ZERO; val before = b[k] ?: BigDecimal.ZERO
                    mapOf("category" to k.first, "currency" to k.second, "this_month" to now.toDouble(), "last_month" to before.toDouble(), "change_percent" to if (before.signum() > 0) now.subtract(before).multiply(BigDecimal(100)).divide(before, 0, RoundingMode.HALF_UP).toInt() else null)
                }.sortedByDescending { (it["this_month"] as Double) }
                Outcome.done(if (rows.isEmpty()) "No hay gastos en estos dos meses." else "Comparación ${lastM} → ${thisM} (mes en curso incompleto): " + rows.take(4).joinToString("; ") { "${it["category"]} ${it["change_percent"]?.let { p -> (if ((p as Int) > 0) "+" else "") + "$p %" } ?: "nuevo"}" } + ".", jsonOf("action" to "compare_months", "rows" to rows), src)
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ cashflow_forecast

class CashflowForecastSkill : Skill {
    override val id = "cashflow_forecast"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val col = ctx.recurring()
        val cur = input.str("currency") ?: "CRC"
        val src = listOf(SkillSource("Registro financiero local", kind = "local"))
        return when (input.str("action")) {
            "add_recurring" -> {
                val name = input.str("name") ?: return Outcome.invalid("Falta el nombre (p. ej. «alquiler»).")
                val amount = input.double("amount")?.takeIf { it > 0 } ?: return Outcome.invalid("Falta el monto.")
                val day = input.int("day_of_month") ?: 1
                val item = RecurringItem(name, input.str("type") ?: "expense", BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString(), cur, day)
                val id = col.nextId(); col.save(id, item)
                Outcome.done("Agregué ${if (item.type == "income") "el ingreso" else "el gasto"} fijo \"$name\": ${BigDecimal(item.amount).money(cur)} el día $day de cada mes.", jsonOf("action" to "add_recurring", "item" to mapOf("id" to id, "name" to name, "amount" to amount, "type" to item.type, "day_of_month" to day, "currency" to cur)), src)
            }
            "list_recurring" -> {
                val all = col.all()
                Outcome.done(if (all.isEmpty()) "No hay movimientos fijos registrados." else "${all.size} movimientos fijos.", jsonOf("action" to "list_recurring", "items" to all.map { (id, r) -> mapOf("id" to id, "name" to r.name, "type" to r.type, "amount" to BigDecimal(r.amount).toDouble(), "currency" to r.currency, "day_of_month" to r.dayOfMonth) }), src)
            }
            "delete_recurring" -> {
                val id = input.str("recurring_id") ?: return Outcome.invalid("Falta recurring_id.")
                val r = col.get(id) ?: return Outcome.fail("not_found", "No existe el movimiento fijo $id.", "Pide la lista.")
                if (!ctx.confirm("Borrar el movimiento fijo \"${r.name}\".")) return Outcome.notConfirmed()
                col.delete(id)
                Outcome.done("Borré \"${r.name}\".", jsonOf("action" to "delete_recurring", "item" to mapOf("id" to id, "name" to r.name)), src)
            }
            "forecast" -> forecast(ctx, input, cur, src)
            else -> Outcome.invalid("Acción no soportada.")
        }
    }

    private suspend fun forecast(ctx: SkillContext, input: JsonObject, cur: String, src: List<SkillSource>): SkillOutcome {
        val months = (input.int("months") ?: 3).coerceIn(1, 12)
        val today = ctx.time.zonedNow().toLocalDate()
        val tx = ctx.transactions().values().filter { it.currency == cur }
        val recurring = ctx.recurring().values().filter { it.currency == cur }
        val warnings = mutableListOf<String>()
        val start = input.double("starting_balance")?.let { BigDecimal.valueOf(it) }
            ?: tx.fold(BigDecimal.ZERO) { acc, t -> if (t.type == "income") acc + BigDecimal(t.amount) else acc - BigDecimal(t.amount) }.also { warnings += "Saldo inicial estimado como la suma de todos tus movimientos registrados en $cur; indícalo si no es correcto." }
        // Gasto variable: promedio de los 3 meses completos anteriores, sin contar los fijos.
        val recurringNames = recurring.map { TextNormalizer.fold(it.name) }
        val prevMonths = (1..3).map { YearMonth.from(today).minusMonths(it.toLong()) }
        val variable = prevMonths.map { m -> tx.filter { it.type == "expense" && it.date.startsWith(m.toString()) && recurringNames.none { n -> TextNormalizer.fold(it.description).contains(n) } }.sumOf { BigDecimal(it.amount) } }
        val monthsWithData = variable.count { it.signum() > 0 }
        val avgVariable = if (monthsWithData > 0) variable.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(monthsWithData), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        if (monthsWithData == 0) warnings += "Sin historial de gastos variables: la proyección solo incluye los movimientos fijos."
        val expected = ctx.quotes().values().filter { it.status == "accepted" && it.currency == cur }
        val expectedIncome = expected.fold(BigDecimal.ZERO) { acc, q -> acc + Quotes.totals(q).third }
        if (expected.isNotEmpty()) warnings += "Incluyo ${expected.size} cotizaciones aceptadas como ingreso esperado del primer mes."
        var balance = start
        val rows = (1..months).map { i ->
            val m = YearMonth.from(today).plusMonths(i.toLong() - 1)
            val inc = recurring.filter { it.type == "income" }.fold(BigDecimal.ZERO) { a, r -> a + BigDecimal(r.amount) } + if (i == 1) expectedIncome else BigDecimal.ZERO
            val fixed = recurring.filter { it.type == "expense" }.fold(BigDecimal.ZERO) { a, r -> a + BigDecimal(r.amount) }
            val out = fixed + avgVariable
            balance = balance + inc - out
            mapOf("month" to m.toString(), "income" to inc.toDouble(), "fixed_expenses" to fixed.toDouble(), "variable_expenses" to avgVariable.toDouble(), "ending_balance" to balance.toDouble())
        }
        val negative = rows.firstOrNull { (it["ending_balance"] as Double) < 0 }
        val msg = "Proyección a $months meses en $cur: saldo final ${BigDecimal(rows.last()["ending_balance"].toString()).money(cur)}." + (negative?.let { " Atención: el saldo sería negativo en ${it["month"]}." } ?: "")
        return Outcome.done(msg, jsonOf("action" to "forecast", "currency" to cur, "starting_balance" to start.toDouble(), "months" to rows, "first_negative_month" to negative?.get("month"),
            "assumptions" to listOf("Movimientos fijos según lo registrado", "Gasto variable = promedio de los últimos meses con datos", "No incluye imprevistos ni cambios de precios")), src, warnings,
            listOf(ResultCard.Info("Flujo de caja ($cur)", rows.map { "${it["month"]}: saldo ${BigDecimal(it["ending_balance"].toString()).money(cur)}" })))
    }
}

// ============================================================ invoice_quote_creator

internal object Quotes {
    /** (subtotal, impuesto, total) con redondeo comercial a 2 decimales. */
    fun totals(q: Quote): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val subtotal = q.lines.fold(BigDecimal.ZERO) { a, l -> a + BigDecimal(l.quantity).multiply(BigDecimal(l.unitPrice)) }.setScale(2, RoundingMode.HALF_UP)
        val tax = subtotal.multiply(BigDecimal(q.taxRate)).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        return Triple(subtotal, tax, subtotal + tax)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun html(q: Quote, issuer: String?): String {
        val (sub, tax, total) = totals(q)
        val title = if (q.kind == "proforma") "Factura proforma" else "Cotización"
        return buildString {
            append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><title>$title ${esc(q.number)}</title><style>body{font-family:system-ui,sans-serif;max-width:760px;margin:24px auto;padding:0 16px}table{width:100%;border-collapse:collapse}td,th{border-bottom:1px solid #ddd;padding:6px;text-align:left}td.n,th.n{text-align:right}.t{font-weight:bold}.note{color:#666;font-size:.85rem}</style></head><body>")
            append("<h1>$title ${esc(q.number)}</h1>")
            issuer?.let { append("<p><strong>").append(esc(it)).append("</strong></p>") }
            append("<p>Cliente: ").append(esc(q.client)).append("<br>Fecha: ").append(q.date).append("<br>Válida hasta: ").append(q.validUntil).append("</p>")
            append("<table><tr><th>Descripción</th><th class=\"n\">Cant.</th><th class=\"n\">Precio unit.</th><th class=\"n\">Importe</th></tr>")
            q.lines.forEach { l -> append("<tr><td>").append(esc(l.description)).append("</td><td class=\"n\">").append(l.quantity).append("</td><td class=\"n\">").append(Money.format(BigDecimal(l.unitPrice), q.currency)).append("</td><td class=\"n\">").append(Money.format(BigDecimal(l.quantity).multiply(BigDecimal(l.unitPrice)), q.currency)).append("</td></tr>") }
            append("<tr><td colspan=3 class=\"n\">Subtotal</td><td class=\"n\">").append(Money.format(sub, q.currency)).append("</td></tr>")
            append("<tr><td colspan=3 class=\"n\">IVA ${q.taxRate} %</td><td class=\"n\">").append(Money.format(tax, q.currency)).append("</td></tr>")
            append("<tr><td colspan=3 class=\"n t\">Total</td><td class=\"n t\">").append(Money.format(total, q.currency)).append("</td></tr></table>")
            if (q.notes.isNotBlank()) append("<p>").append(esc(q.notes)).append("</p>")
            append("<p class=\"note\">Documento informativo generado por JARVIS. No es un comprobante electrónico de Hacienda.</p></body></html>")
        }
    }

    fun text(q: Quote): String {
        val (sub, tax, total) = totals(q)
        return "${if (q.kind == "proforma") "Proforma" else "Cotización"} ${q.number} para ${q.client}\n" + q.lines.joinToString("\n") { "- ${it.description}: ${it.quantity} × ${Money.format(BigDecimal(it.unitPrice), q.currency)}" } +
            "\nSubtotal ${Money.format(sub, q.currency)} · IVA ${q.taxRate} % ${Money.format(tax, q.currency)} · Total ${Money.format(total, q.currency)}\nVálida hasta ${q.validUntil}."
    }
}

class InvoiceQuoteCreatorSkill : Skill {
    override val id = "invoice_quote_creator"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val col = ctx.quotes()
        val today = ctx.time.zonedNow().toLocalDate()
        val warnings = mutableListOf<String>()
        fun view(id: String, q: Quote): Map<String, Any?> { val (s, t, tot) = Quotes.totals(q); return mapOf("id" to id, "number" to q.number, "kind" to q.kind, "client" to q.client, "currency" to q.currency, "subtotal" to s.toDouble(), "tax" to t.toDouble(), "total" to tot.toDouble(), "status" to q.status, "valid_until" to q.validUntil) }
        return when (input.str("action")) {
            "create" -> {
                val client = input.str("client") ?: return Outcome.invalid("Falta el cliente.")
                val lines = input.arr("lines")?.map { it.jsonObject }?.map { l ->
                    val qty = l.double("quantity")?.takeIf { it > 0 } ?: return Outcome.invalid("Cada línea necesita cantidad positiva.")
                    val price = l.double("unit_price")?.takeIf { it >= 0 } ?: return Outcome.invalid("Cada línea necesita precio unitario.")
                    QuoteLine(l.str("description") ?: return Outcome.invalid("Cada línea necesita descripción."), BigDecimal.valueOf(qty).stripTrailingZeros().toPlainString(), BigDecimal.valueOf(price).stripTrailingZeros().toPlainString())
                }?.takeIf { it.isNotEmpty() } ?: return Outcome.invalid("Incluye al menos una línea (descripción, cantidad, precio).")
                val kind = input.str("kind") ?: "quote"
                val prefix = if (kind == "proforma") "PRO" else "COT"
                val year = today.year
                val seq = col.values().count { it.number.startsWith("$prefix-$year-") } + 1
                val q = Quote("$prefix-$year-${seq.toString().padStart(4, '0')}", kind, client, input.str("client_email"), lines,
                    BigDecimal.valueOf(input.double("tax_rate") ?: 13.0).stripTrailingZeros().toPlainString(), input.str("currency") ?: "CRC",
                    today.toString(), today.plusDays((input.int("valid_days") ?: 15).toLong()).toString(), input.str("notes") ?: "")
                val id = col.nextId()
                col.save(id, q)
                val file = if (input.bool("save") != false) ctx.saveDoc("${q.number}.html", "text/html", Quotes.html(q, input.str("issuer")), warnings) else null
                val (_, _, total) = Quotes.totals(q)
                warnings += "No es una factura electrónica de Hacienda; para facturar oficialmente usa un sistema de facturación electrónica autorizado."
                Outcome.done("Creé la ${if (kind == "proforma") "proforma" else "cotización"} ${q.number} para $client por ${Money.format(total, q.currency)} (IVA ${q.taxRate} % incluido).",
                    jsonOf("action" to "create", "quote" to view(id, q), "file" to file, "text" to Quotes.text(q)), warnings = warnings,
                    cards = listOf(ResultCard.Info(q.number, Quotes.text(q).lines())))
            }
            "list" -> {
                val all = col.all().sortedByDescending { it.second.date }
                Outcome.done(if (all.isEmpty()) "No hay cotizaciones." else "${all.size} documentos: " + all.groupingBy { it.second.status }.eachCount().entries.joinToString { "${it.value} ${it.key}" } + ".", jsonOf("action" to "list", "quotes" to all.map { view(it.first, it.second) }))
            }
            "update_status" -> {
                val id = input.str("quote_id") ?: return Outcome.invalid("Falta quote_id.")
                val q = col.get(id) ?: return Outcome.fail("not_found", "No existe la cotización $id.", "Pide la lista.")
                val status = input.str("status") ?: return Outcome.invalid("Falta el estado.")
                if (status == "paid") return execute(jsonOf("action" to "record_payment", "quote_id" to id), ctx)
                col.save(id, q.copy(status = status))
                Outcome.done("${q.number}: estado $status.", jsonOf("action" to "update_status", "quote" to view(id, q.copy(status = status))))
            }
            "record_payment" -> {
                val id = input.str("quote_id") ?: return Outcome.invalid("Falta quote_id.")
                val q = col.get(id) ?: return Outcome.fail("not_found", "No existe la cotización $id.", "Pide la lista.")
                if (q.status == "paid") return Outcome.fail("already_paid", "${q.number} ya estaba registrada como pagada.", "No se registró otro ingreso para no duplicarlo.")
                val (_, _, total) = Quotes.totals(q)
                val tx = ctx.transactions()
                ctx.services.store.putAll(listOf(col.record(id, q.copy(status = "paid")), tx.record(tx.nextId(), Transaction("income", total.toPlainString(), q.currency, "ingresos", "Pago ${q.number} — ${q.client}", today.toString(), source = "invoice", reference = q.number))))
                Outcome.done("Registré el pago de ${q.number}: ingreso de ${Money.format(total, q.currency)}.", jsonOf("action" to "record_payment", "quote" to view(id, q.copy(status = "paid"))))
            }
            "send" -> {
                val id = input.str("quote_id") ?: return Outcome.invalid("Falta quote_id.")
                val q = col.get(id) ?: return Outcome.fail("not_found", "No existe la cotización $id.", "Pide la lista.")
                val to = input.str("client_email") ?: q.clientEmail ?: return Outcome.invalid("Falta el correo del cliente.")
                val r = ctx.tool("compose_email", jsonOf("to" to listOf(to), "subject" to "${if (q.kind == "proforma") "Proforma" else "Cotización"} ${q.number}", "body" to "Estimado/a ${q.client}:\n\nLe comparto la ${if (q.kind == "proforma") "proforma" else "cotización"} solicitada:\n\n${Quotes.text(q)}\n\nQuedo atento a sus comentarios.\n\nSaludos cordiales."))
                if (!r.ok) return Outcome.fromTool(r, "Abrir el correo")
                col.save(id, q.copy(status = "sent"))
                Outcome.done("Abrí el correo con la cotización ${q.number}; adjunta el HTML guardado si lo deseas y envíalo tú.", jsonOf("action" to "send", "quote" to view(id, q.copy(status = "sent"))))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}
