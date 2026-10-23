package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.json.double
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.str
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.text.TextNormalizer
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Inventario local compartido: el del negocio (stock_control) y el del hogar (home_inventory)
 * usan la misma colección y la misma lógica, separados por ámbito.
 */
abstract class InventorySkill(private val scope: String) : Skill {
    protected val isHome get() = scope == "home"

    protected fun view(id: String, i: InventoryItem) = mapOf(
        "id" to id, "name" to i.name, "quantity" to i.quantity, "unit" to i.unit, "location" to i.location,
        "min_quantity" to i.minQuantity, "unit_cost" to i.unitCost, "currency" to i.currency, "barcode" to i.barcode,
        "warranty_until" to i.warrantyUntil, "serial" to i.serial,
    )

    protected suspend fun items(ctx: SkillContext) = ctx.inventory().all().filter { it.second.scope == scope }

    /** Busca por id, código de barras o nombre (sin tildes; coincidencia parcial). */
    protected suspend fun find(ctx: SkillContext, input: JsonObject): List<Pair<String, InventoryItem>> {
        val all = items(ctx)
        input.str("item_id")?.let { id -> return all.filter { it.first == id } }
        input.str("barcode")?.let { code -> all.filter { it.second.barcode == code }.takeIf { it.isNotEmpty() }?.let { return it } }
        val q = TextNormalizer.fold(input.str("name") ?: input.str("query") ?: return emptyList())
        val exact = all.filter { TextNormalizer.fold(it.second.name) == q }
        if (exact.isNotEmpty()) return exact
        val tokens = TextNormalizer.searchTokens(q)
        return all.filter { (_, i) -> val t = TextNormalizer.searchTokens(i.name + " " + (i.location ?: "")); tokens.isNotEmpty() && tokens.all { q1 -> t.any { it.startsWith(q1) || q1.startsWith(it) } } }
    }

    protected fun totals(list: List<Pair<String, InventoryItem>>, home: Boolean): List<Map<String, Any>> =
        list.mapNotNull { (_, i) ->
            val unit = i.unitCost?.toBigDecimalOrNull() ?: return@mapNotNull null
            val cur = i.currency ?: return@mapNotNull null
            cur to (if (home) unit else unit * BigDecimal(i.quantity))
        }.groupBy({ it.first }, { it.second }).map { (cur, v) -> mapOf("currency" to cur, "value" to v.fold(BigDecimal.ZERO, BigDecimal::add).toDouble()) }

    protected suspend fun delete(ctx: SkillContext, input: JsonObject): SkillOutcome {
        val matches = find(ctx, input)
        if (matches.size != 1) return notFound(matches)
        val (id, item) = matches.single()
        if (!ctx.confirm("Borrar \"${item.name}\" del inventario${if (isHome) " del hogar" else ""}. No se puede deshacer.")) return Outcome.notConfirmed()
        ctx.inventory().delete(id)
        return Outcome.done("Borré \"${item.name}\" del inventario.", jsonOf("action" to "delete_item", "item" to view(id, item)))
    }

    protected fun notFound(matches: List<Pair<String, InventoryItem>>): SkillOutcome =
        if (matches.isEmpty()) Outcome.fail("not_found", "No encontré ese artículo en el inventario.", "Pide la lista o indica el nombre exacto.")
        else Outcome.fail("ambiguous", "Hay ${matches.size} artículos que coinciden: ${matches.take(5).joinToString { "${it.second.name} (id ${it.first})" }}.", "Indica el id del artículo.")

    protected fun validDate(s: String?): Boolean = s == null || runCatching { LocalDate.parse(s) }.isSuccess
}

class StockControlSkill : InventorySkill("business") {
    override val id = "stock_control"

    override fun parseText(text: String): JsonObject? {
        val f = TextNormalizer.canonical(text)
        return when {
            SkillText.containsAny(f, "por agotarse", "existencias bajas", "poco stock", "se esta acabando", "se estan acabando") -> jsonOf("action" to "low_stock")
            SkillText.containsAny(f, "cuanto vale mi inventario", "valor del inventario") -> jsonOf("action" to "value")
            f.startsWith("muestra el inventario") || f == "inventario" || f.startsWith("lista el inventario") -> jsonOf("action" to "list")
            else -> null
        }
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val col = ctx.inventory()
        val src = listOf(SkillSource("Inventario local del negocio", kind = "local"))
        return when (input.str("action")) {
            "add_item" -> {
                val name = input.str("name")?.trim()?.takeIf { it.length >= 2 } ?: return Outcome.invalid("Falta el nombre del producto.")
                if (find(ctx, jsonOf("name" to name)).any { TextNormalizer.fold(it.second.name) == TextNormalizer.fold(name) }) {
                    return Outcome.fail("duplicate", "\"$name\" ya existe en el inventario.", "Registra un movimiento (entrada) en lugar de crearlo de nuevo.")
                }
                val cost = input.double("unit_cost")
                val item = InventoryItem(
                    scope = "business", name = name, quantity = input.double("quantity") ?: 0.0, unit = input.str("unit") ?: "unidades",
                    minQuantity = input.double("min_quantity"), unitCost = cost?.toBigDecimal()?.toPlainString(),
                    currency = if (cost != null) input.str("currency") ?: "CRC" else input.str("currency"), barcode = input.str("barcode"),
                )
                val id = col.nextId()
                col.save(id, item)
                Outcome.done("Agregué \"$name\" con ${fmt(item.quantity)} ${item.unit} (id $id).", jsonOf("action" to "add_item", "item" to view(id, item)), src)
            }
            "record_movement" -> {
                val delta = input.double("delta") ?: return Outcome.invalid("Indica la cantidad del movimiento (delta: positiva para entradas, negativa para salidas).")
                val matches = find(ctx, input)
                if (matches.size != 1) return notFound(matches)
                val (id, item) = matches.single()
                val next = item.quantity + delta
                if (next < 0) return Outcome.fail("insufficient_stock", "No hay suficiente \"${item.name}\": hay ${fmt(item.quantity)} ${item.unit} y la salida es de ${fmt(-delta)}.", "Revisa la cantidad o registra primero la entrada.")
                val reason = input.str("reason") ?: if (delta < 0) "salida" else "entrada"
                val mv = StockMovement(id, delta, reason, ctx.nowIso(), next)
                val updated = item.copy(quantity = next)
                // Movimiento y existencia se guardan juntos.
                ctx.services.store.putAll(listOf(col.record(id, updated), ctx.movements().record(ctx.movements().nextId(), mv)))
                val alert = if (updated.minQuantity != null && next <= updated.minQuantity) " Atención: quedó en o bajo el mínimo (${fmt(updated.minQuantity)})." else ""
                Outcome.done("Registré $reason de ${fmt(kotlin.math.abs(delta))} ${item.unit} de \"${item.name}\". Existencia: ${fmt(next)}.$alert",
                    jsonOf("action" to "record_movement", "item" to view(id, updated), "movement" to mapOf("delta" to delta, "reason" to reason, "resulting_quantity" to next)), src)
            }
            "list", "find" -> {
                val list = if (input.str("action") == "find") find(ctx, input) else items(ctx)
                if (list.isEmpty()) return Outcome.done(if (input.str("action") == "find") "No encontré artículos que coincidan." else "El inventario del negocio está vacío.", jsonOf("action" to input.str("action"), "items" to emptyList<Any>()), src)
                Outcome.done("Hay ${list.size} artículos.", jsonOf("action" to input.str("action"), "items" to list.map { view(it.first, it.second) }), src,
                    cards = listOf(ResultCard.Info("Inventario", list.take(30).map { (id, i) -> "#$id ${i.name}: ${fmt(i.quantity)} ${i.unit}" + (i.minQuantity?.let { m -> if (i.quantity <= m) " ⚠ mínimo ${fmt(m)}" else "" } ?: "") })))
            }
            "low_stock" -> {
                val low = items(ctx).filter { (_, i) -> i.minQuantity != null && i.quantity <= i.minQuantity }
                Outcome.done(if (low.isEmpty()) "Ningún producto está en o bajo su mínimo." else "${low.size} productos están por agotarse: ${low.joinToString { "${it.second.name} (${fmt(it.second.quantity)})" }}.",
                    jsonOf("action" to "low_stock", "items" to low.map { view(it.first, it.second) }), src)
            }
            "value" -> {
                val list = items(ctx)
                val t = totals(list, home = false)
                val missing = list.count { it.second.unitCost == null }
                Outcome.done(
                    if (t.isEmpty()) "No hay costos registrados para calcular el valor." else "Valor del inventario: " + t.joinToString(", ") { Money.format(BigDecimal(it["value"].toString()), it["currency"] as String) } + "." + if (missing > 0) " ($missing artículos sin costo no se incluyen.)" else "",
                    jsonOf("action" to "value", "totals" to t), src,
                )
            }
            "delete_item" -> delete(ctx, input)
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

class HomeInventorySkill : InventorySkill("home") {
    override val id = "home_inventory"

    override fun parseText(text: String): JsonObject? {
        val f = TextNormalizer.canonical(text)
        Regex("^donde (guarde|deje|esta|estan|puse) (el |la |los |las |mi |mis )?(.+)$").find(f)?.let { return jsonOf("action" to "find", "query" to it.groupValues[3].trim()) }
        if (SkillText.containsAny(f, "garantias", "garantia")) return jsonOf("action" to "warranties", "days" to 90)
        return null
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val col = ctx.inventory()
        val src = listOf(SkillSource("Inventario local del hogar", kind = "local"))
        return when (input.str("action")) {
            "add_item" -> {
                val name = input.str("name")?.trim()?.takeIf { it.length >= 2 } ?: return Outcome.invalid("Falta el nombre del objeto.")
                if (!validDate(input.str("purchase_date")) || !validDate(input.str("warranty_until"))) return Outcome.invalid("Las fechas deben tener formato AAAA-MM-DD.")
                val value = input.double("value")
                val item = InventoryItem(
                    scope = "home", name = name, quantity = input.double("quantity") ?: 1.0, location = input.str("location"),
                    unitCost = value?.toBigDecimal()?.toPlainString(), currency = if (value != null) input.str("currency") ?: "CRC" else null,
                    purchaseDate = input.str("purchase_date"), warrantyUntil = input.str("warranty_until"), serial = input.str("serial"), notes = input.str("notes") ?: "",
                )
                val id = col.nextId()
                col.save(id, item)
                Outcome.done("Guardé \"$name\"" + (item.location?.let { " en $it" } ?: "") + " (id $id).", jsonOf("action" to "add_item", "item" to view(id, item)), src)
            }
            "list", "find" -> {
                val list = if (input.str("action") == "find") find(ctx, input) else items(ctx)
                if (list.isEmpty()) return Outcome.done(if (input.str("action") == "find") "No tengo registrado ese objeto en el inventario de la casa." else "El inventario de la casa está vacío.", jsonOf("action" to input.str("action"), "items" to emptyList<Any>()), src)
                val msg = if (input.str("action") == "find") list.joinToString("; ") { (_, i) -> "${i.name}: ${i.location ?: "sin ubicación registrada"}" } else "Tienes ${list.size} objetos registrados."
                val byPlace = list.groupBy { it.second.location ?: "sin ubicación" }
                Outcome.done(msg, jsonOf("action" to input.str("action"), "items" to list.map { view(it.first, it.second) }), src,
                    cards = listOf(ResultCard.Info("Inventario del hogar", byPlace.flatMap { (place, l) -> listOf("$place:") + l.map { "  • ${it.second.name}" } })))
            }
            "warranties" -> {
                val days = (input.int("days") ?: 90).toLong()
                val today = ctx.time.zonedNow().toLocalDate()
                val soon = items(ctx).mapNotNull { (id, i) -> i.warrantyUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { Triple(id, i, it) } }
                    .filter { !it.third.isBefore(today) && !it.third.isAfter(today.plusDays(days)) }.sortedBy { it.third }
                Outcome.done(
                    if (soon.isEmpty()) "Ninguna garantía vence en los próximos $days días." else "Garantías por vencer: " + soon.joinToString("; ") { "${it.second.name} (${it.third})" } + ".",
                    jsonOf("action" to "warranties", "items" to soon.map { view(it.first, it.second) }), src,
                )
            }
            "value" -> {
                val list = items(ctx)
                val t = totals(list, home = true)
                Outcome.done(if (t.isEmpty()) "No hay valores registrados." else "Valor registrado: " + t.joinToString(", ") { Money.format(BigDecimal(it["value"].toString()), it["currency"] as String) } + ".",
                    jsonOf("action" to "value", "totals" to t), src)
            }
            "delete_item" -> delete(ctx, input)
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

internal fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else Tabular.round(d).toString()
