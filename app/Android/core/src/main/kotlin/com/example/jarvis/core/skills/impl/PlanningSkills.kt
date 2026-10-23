package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.double
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.str
import com.example.jarvis.core.json.strings
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.tasks.TaskFilter
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.tasks.TaskPriority
import com.example.jarvis.core.text.TextNormalizer
import com.example.jarvis.core.tools.local.NaturalDateParser
import com.example.jarvis.core.tools.local.SpanishDates
import com.example.jarvis.core.weather.Place
import com.example.jarvis.core.weather.WeatherException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// ============================================================ schedule_optimizer

class ScheduleOptimizerSkill : Skill {
    override val id = "schedule_optimizer"

    data class Block(val start: LocalTime, val end: LocalTime, val type: String, val title: String, val taskId: Long? = null)

    override fun parseText(text: String): JsonObject? {
        val f = TextNormalizer.canonical(text)
        if (!SkillText.containsAny(f, "organiza mi dia", "organiza mi agenda", "planifica mi dia", "acomoda mis tareas", "optimiza mi agenda", "organiza manana")) return null
        return jsonOf("date" to if (f.contains("manana")) "tomorrow" else "today")
    }

    fun minutes(t: TaskItem, default: Int): Int {
        val txt = TextNormalizer.fold(t.title + " " + t.description)
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(h|hora|horas)\\b").find(txt)?.let { return (it.groupValues[1].replace(',', '.').toDouble() * 60).toInt().coerceIn(10, 480) }
        Regex("(\\d+)\\s*(min|minutos)\\b").find(txt)?.let { return it.groupValues[1].toInt().coerceIn(5, 480) }
        return default
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val now = ctx.time.zonedNow().toLocalDateTime()
        val date = when (val d = input.str("date") ?: "today") { "today" -> now.toLocalDate(); "tomorrow" -> now.toLocalDate().plusDays(1); else -> runCatching { LocalDate.parse(d) }.getOrNull() ?: return Outcome.invalid("Fecha inválida (usa today, tomorrow o AAAA-MM-DD).") }
        val ws = runCatching { LocalTime.parse(input.str("work_start") ?: "08:00") }.getOrNull() ?: return Outcome.invalid("work_start debe ser HH:mm.")
        val we = runCatching { LocalTime.parse(input.str("work_end") ?: "17:00") }.getOrNull() ?: return Outcome.invalid("work_end debe ser HH:mm.")
        if (!we.isAfter(ws)) return Outcome.invalid("El fin de la jornada debe ser posterior al inicio.")
        val default = input.int("default_task_minutes") ?: 60
        val buffer = (input.int("buffer_minutes") ?: 10).toLong()
        val warnings = mutableListOf<String>()

        val events = ctx.calendar(date, date)
        if (events == null) warnings += "Sin permiso de calendario: planifiqué sin tus eventos."
        val busy = mutableListOf<Block>()
        events?.filter { !it.allDay }?.forEach { e -> busy += Block(e.start.toLocalTime(), (e.end ?: e.start.plusHours(1)).toLocalTime().let { if (it.isBefore(e.start.toLocalTime())) LocalTime.MAX else it }, "evento", e.title) }
        val pending = ctx.services.tasks.list(TaskFilter.PENDING)
        val fixed = pending.filter { it.hasTime && it.due?.toLocalDate() == date }
        fixed.forEach { t -> val s = t.due!!.toLocalTime(); busy += Block(s, s.plusMinutes(minutes(t, default).toLong()), "tarea fija", t.title, t.id) }
        val flexible = pending.filter { t -> t !in fixed && ((t.due?.toLocalDate() == date) || (input.bool("include_overdue") != false && t.isOverdue(now)) || (input.bool("include_undated") != false && t.due == null && t.priority == TaskPriority.HIGH)) }
            .sortedWith(compareByDescending<TaskItem> { it.isOverdue(now) }.thenByDescending { it.priority.ordinal }.thenBy { it.due ?: LocalDateTime.MAX })

        var dayStart = ws
        if (date == now.toLocalDate() && now.toLocalTime().isAfter(ws)) dayStart = now.toLocalTime().truncatedTo(ChronoUnit.HOURS).plusMinutes(((now.minute / 15) + 1) * 15L)
        if (!dayStart.isBefore(we)) return Outcome.done("La jornada de hoy ya terminó; pide «organiza mañana».", jsonOf("date" to date.toString(), "blocks" to emptyList<Any>(), "unscheduled" to flexible.map { it.title }, "utilization_percent" to 0))
        // Huecos libres dentro de la jornada.
        val sortedBusy = busy.sortedBy { it.start }
        val free = mutableListOf<Pair<LocalTime, LocalTime>>()
        var cursor = dayStart
        for (b in sortedBusy) {
            if (b.end <= cursor) continue
            if (b.start > cursor) free += cursor to minOf(b.start.minusMinutes(buffer), we)
            cursor = maxOf(cursor, b.end.plusMinutes(buffer))
            if (cursor >= we) break
        }
        if (cursor < we) free += cursor to we
        val slots = free.filter { it.second > it.first }.map { it.first to it.second }.toMutableList()
        val placed = mutableListOf<Block>()
        val unscheduled = mutableListOf<String>()
        for (t in flexible) {
            val need = minutes(t, default).toLong()
            val i = slots.indexOfFirst { ChronoUnit.MINUTES.between(it.first, it.second) >= need }
            if (i < 0) { unscheduled += t.title; continue }
            val (s, e) = slots[i]
            val end = s.plusMinutes(need)
            placed += Block(s, end, if (t.isOverdue(now)) "tarea vencida" else "tarea", t.title, t.id)
            val nextStart = end.plusMinutes(buffer)
            if (nextStart < e) slots[i] = nextStart to e else slots.removeAt(i)
        }
        val blocks = (sortedBusy + placed).filter { it.end > dayStart && it.start < we }.sortedBy { it.start }
        val workMinutes = ChronoUnit.MINUTES.between(dayStart, we).coerceAtLeast(1)
        val used = blocks.sumOf { ChronoUnit.MINUTES.between(maxOf(it.start, dayStart), minOf(it.end, we)).coerceAtLeast(0) }
        val util = (used * 100 / workMinutes).toInt()
        if (unscheduled.isNotEmpty()) warnings += "No caben ${unscheduled.size} tareas: ${unscheduled.joinToString()}. Muévelas a otro día o acorta su duración."
        if (util > 90) warnings += "Agenda al ${util} %: deja margen para imprevistos."
        val data = jsonOf("date" to date.toString(), "blocks" to blocks.map { mapOf("start" to it.start.toString(), "end" to it.end.toString(), "type" to it.type, "title" to it.title, "task_id" to it.taskId) },
            "unscheduled" to unscheduled, "utilization_percent" to util, "free_slots" to slots.map { mapOf("start" to it.first.toString(), "end" to it.second.toString()) })
        val msg = "Plan para ${SpanishDates.longDate(date)}: ${placed.size} tareas ubicadas entre ${blocks.size - placed.size} compromisos, ocupación $util %." + if (unscheduled.isNotEmpty()) " ${unscheduled.size} no caben." else ""
        val cards = listOf(ResultCard.Info("Agenda optimizada", blocks.map { "${it.start}–${it.end} · ${it.title} (${it.type})" } + unscheduled.map { "Sin espacio: $it" }))
        // Sin calendario el plan es parcial; con calendario, los avisos (tareas sin espacio) no lo invalidan.
        return if (events == null) Outcome.partial(msg, data, warnings, cards = cards) else Outcome.done(msg, data, warnings = warnings, cards = cards)
    }
}

// ============================================================ renewal_monitor

class RenewalMonitorSkill : Skill {
    override val id = "renewal_monitor"

    override fun parseText(text: String): JsonObject? =
        if (SkillText.containsAny(text, "que vence pronto", "que se vence", "renovaciones", "que tengo que renovar")) jsonOf("action" to "upcoming", "days" to 60) else null

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val col = ctx.renewals()
        val today = ctx.time.zonedNow().toLocalDate()
        fun view(id: String, r: Renewal) = mapOf("id" to id, "name" to r.name, "kind" to r.kind, "expires_on" to r.expiresOn, "days_left" to ChronoUnit.DAYS.between(today, LocalDate.parse(r.expiresOn)), "remind_days_before" to r.remindDaysBefore, "task_id" to r.taskId)
        return when (input.str("action")) {
            "add" -> {
                val name = input.str("name")?.trim() ?: return Outcome.invalid("Falta el nombre (p. ej. «marchamo», «pasaporte»).")
                val warnings = mutableListOf<String>()
                val expires = input.str("expires_on")?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() ?: NaturalDateParser.parse(s, ctx.time.zonedNow())?.dateTime?.toLocalDate() ?: return Outcome.invalid("No entendí la fecha \"$s\".") }
                    ?: if (TextNormalizer.fold(name).contains("marchamo")) LocalDate.of(today.year, 12, 31).also { warnings += "El marchamo vence el 31 de diciembre de cada año." }
                    else return Outcome.invalid("Indica la fecha de vencimiento (expires_on).")
                if (expires.isBefore(today)) warnings += "Esa fecha ya pasó: \"$name\" está vencido."
                val days = input.int("remind_days_before") ?: 15
                var remindOn = expires.minusDays(days.toLong())
                if (!remindOn.isAfter(today)) { remindOn = today.plusDays(1); warnings += "El aviso se programa para mañana porque faltan menos de $days días." }
                val r = ctx.tool("create_task", jsonOf("title" to "Renovar: $name", "description" to "Vence el $expires.", "due" to "${remindOn}T09:00", "remind" to true, "priority" to "HIGH"))
                val taskId = (r.data as? JsonObject)?.str("task_id")?.toLongOrNull() ?: (r.data as? JsonObject)?.get("task_id")?.jsonPrimitive?.content?.toLongOrNull()
                if (!r.ok) warnings += "No se pudo programar el aviso: ${r.message}"
                val id = col.nextId()
                val ren = Renewal(name, input.str("kind") ?: "otro", expires.toString(), days, taskId, input.str("notes") ?: "")
                col.save(id, ren)
                Outcome.done("Registré \"$name\": vence el $expires; te aviso el $remindOn.", jsonOf("action" to "add", "renewal" to view(id, ren)), warnings = warnings)
            }
            "list", "upcoming" -> {
                val days = (input.int("days") ?: 60).toLong()
                val all = col.all().sortedBy { it.second.expiresOn }
                val list = if (input.str("action") == "upcoming") all.filter { !LocalDate.parse(it.second.expiresOn).isAfter(today.plusDays(days)) } else all
                val expired = list.count { LocalDate.parse(it.second.expiresOn).isBefore(today) }
                Outcome.done(if (list.isEmpty()) "No hay renovaciones${if (input.str("action") == "upcoming") " en los próximos $days días" else " registradas"}." else list.joinToString("; ") { (_, r) -> "${r.name}: ${r.expiresOn}" } + "." + if (expired > 0) " $expired ya vencieron." else "",
                    jsonOf("action" to input.str("action"), "renewals" to list.map { view(it.first, it.second) }), listOf(SkillSource("Renovaciones registradas", kind = "local")),
                    cards = listOf(ResultCard.Info("Renovaciones", list.map { (id, r) -> "${r.name}: ${r.expiresOn} (${view(id, r)["days_left"]} días)" })))
            }
            "delete" -> {
                val id = input.str("renewal_id") ?: return Outcome.invalid("Falta renewal_id.")
                val r = col.get(id) ?: return Outcome.fail("not_found", "No existe la renovación $id.", "Pide la lista.")
                if (r.taskId != null) {
                    val del = ctx.tool("delete_task", jsonOf("task_id" to r.taskId))
                    if (!del.ok && del.errorCode == "not_confirmed") return Outcome.notConfirmed()
                } else if (!ctx.confirm("Borrar la renovación \"${r.name}\".")) return Outcome.notConfirmed()
                col.delete(id)
                Outcome.done("Borré la renovación \"${r.name}\" y su aviso.", jsonOf("action" to "delete", "renewal" to mapOf("id" to id, "name" to r.name)))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ travel_planner

class TravelPlannerSkill : Skill {
    override val id = "travel_planner"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val destination = input.str("destination")!!
        val start = runCatching { LocalDate.parse(input.str("start_date")!!) }.getOrNull() ?: return Outcome.invalid("start_date debe ser AAAA-MM-DD.")
        val end = runCatching { LocalDate.parse(input.str("end_date")!!) }.getOrNull() ?: return Outcome.invalid("end_date debe ser AAAA-MM-DD.")
        val today = ctx.time.zonedNow().toLocalDate()
        if (end.isBefore(start)) return Outcome.invalid("La fecha de regreso es anterior a la de salida.")
        val nights = ChronoUnit.DAYS.between(start, end).toInt()
        if (nights > 30) return Outcome.invalid("Planifico viajes de hasta 30 noches.")
        val warnings = mutableListOf<String>()
        val sources = mutableListOf<SkillSource>()

        val found = ctx.tool("find_place", jsonOf("query" to destination))
        val place = (found.data as? JsonObject)?.arr("places")?.firstOrNull()?.jsonObject?.let { p -> Place(p.str("label")!!.substringBefore(','), null, p.str("country"), p.double("lat")!!, p.double("lon")!!) }
        if (place == null) warnings += if (found.ok) "No encontré \"$destination\" en el geocodificador: sin pronóstico." else "Sin conexión para ubicar el destino: ${found.message}"
        else sources += SkillSource("Open-Meteo Geocoding (GeoNames)", "https://open-meteo.com", "api", ctx.nowIso())
        val abroad = place?.country?.let { !TextNormalizer.fold(it).contains("costa rica") } ?: false

        val forecast = if (place != null && !start.isAfter(today.plusDays(15))) try {
            ctx.services.weather.daily(place, (ChronoUnit.DAYS.between(today, end).toInt() + 1).coerceIn(1, 16)).filter { val d = LocalDate.parse(it.date); !d.isBefore(start) && !d.isAfter(end) }
                .also { sources += SkillSource("Open-Meteo (pronóstico)", "https://open-meteo.com", "api", ctx.nowIso()) }
        } catch (e: WeatherException) { warnings += "Pronóstico no disponible: ${e.message}"; emptyList() } else {
            if (place != null) warnings += "El pronóstico solo cubre 16 días; vuelve a consultarlo cerca de la fecha."
            emptyList()
        }
        val maxT = forecast.mapNotNull { it.maxC }.maxOrNull(); val minT = forecast.mapNotNull { it.minC }.minOrNull()
        val rainy = forecast.count { (it.rainProbability ?: 0) >= 50 }

        val packing = mutableListOf("Cédula" + if (abroad) " y pasaporte vigente (mínimo 6 meses)" else "", "Cargador y batería externa", "Medicamentos personales", "Copia digital de documentos")
        if (rainy > 0) packing += "Paraguas o capa (lluvia probable $rainy de ${forecast.size} días)"
        if (maxT != null && maxT >= 27) packing += listOf("Bloqueador solar", "Ropa fresca", "Botella de agua")
        if (minT != null && minT <= 15) packing += listOf("Abrigo", "Ropa en capas")
        if (input.str("purpose") == "negocios") packing += listOf("Ropa formal", "Tarjetas de presentación", "Laptop y adaptador")
        if (abroad) packing += listOf("Adaptador de enchufe (verifica el tipo)", "Seguro de viaje", "Tarjeta habilitada para el exterior")
        val interests = input.strings("interests")
        val itinerary = (0..nights).map { i ->
            val d = start.plusDays(i.toLong())
            val f = forecast.firstOrNull { it.date == d.toString() }
            val plan = when (i) { 0 -> "Llegada, registro en el hospedaje y recorrido cercano"; nights -> "Regreso (revisa la hora de salida)"; else -> interests.getOrNull((i - 1) % interests.size.coerceAtLeast(1))?.let { "Día de $it" } ?: "Día libre para explorar" }
            mapOf("date" to d.toString(), "plan" to plan, "weather" to f?.let { "${it.description}, ${it.minC?.let(::fmt)}–${it.maxC?.let(::fmt)} °C, lluvia ${it.rainProbability ?: "?"} %" })
        }
        val budget = input.double("budget")?.let { b ->
            val cur = input.str("currency") ?: "CRC"
            listOf("hospedaje" to 0.40, "comida" to 0.25, "transporte" to 0.20, "actividades" to 0.15).map { (k, p) -> mapOf("item" to k, "amount" to BigDecimal.valueOf(b * p).setScale(0, RoundingMode.HALF_UP).toDouble(), "currency" to cur) }
                .also { warnings += "Reparto de presupuesto sugerido (40/25/20/15 %), no son precios reales." }
        }
        var requirements: List<Map<String, Any?>>? = null
        if (abroad && place?.country != null) {
            if (!ctx.env.serverConfigured()) warnings += "Para consultar requisitos de entrada a ${place.country} configura el servidor (búsqueda web)."
            else {
                val (hits, r) = ctx.search("requisitos de entrada a ${place.country} para costarricenses", 5)
                if (r.ok) { requirements = hits.map { mapOf("title" to it.title, "url" to it.url, "kind" to Web.kind(it.url)) }; sources += hits.map(Web::source) }
                warnings += "Confirma los requisitos de entrada con fuentes oficiales (consulado o migración del país)."
            }
        }
        var tasksCreated = 0
        if (input.bool("create_tasks") == true) {
            val before = start.minusDays(7).takeIf { it.isAfter(today) } ?: today.plusDays(1)
            for (t in listOfNotNull("Reservar hospedaje en $destination", if (abroad) "Revisar pasaporte y requisitos de entrada" else null, "Preparar maleta para $destination")) {
                if (ctx.tool("create_task", jsonOf("title" to t, "due" to before.toString(), "remind" to true)).ok) tasksCreated++
            }
        }
        val data = jsonOf("destination" to (place?.label ?: destination), "country" to place?.country, "start" to start.toString(), "end" to end.toString(), "nights" to nights,
            "forecast" to forecast.map { mapOf("date" to it.date, "description" to it.description, "min_c" to it.minC, "max_c" to it.maxC, "rain_probability" to it.rainProbability) },
            "itinerary" to itinerary, "packing" to packing, "budget" to budget, "entry_requirements" to requirements, "tasks_created" to tasksCreated)
        val msg = "Plan para ${place?.label ?: destination} del $start al $end ($nights noches)" + (if (forecast.isNotEmpty()) ": ${minT?.let(::fmt)}–${maxT?.let(::fmt)} °C, lluvia probable $rainy días." else ".") + (if (tasksCreated > 0) " Creé $tasksCreated tareas." else "")
        val cards = listOf(ResultCard.Info("Viaje a ${place?.label ?: destination}", itinerary.map { "${it["date"]}: ${it["plan"]}" + (it["weather"]?.let { w -> " — $w" } ?: "") } + listOf("Llevar: " + packing.joinToString())))
        return if (place == null) Outcome.partial(msg, data, warnings, sources, cards) else Outcome.done(msg, data, sources, warnings, cards)
    }
}

// ============================================================ meal_and_grocery_planner

internal object Recipes {
    data class Recipe(val name: String, val tags: Set<String>, val ingredients: List<Triple<String, Double, String>>)

    val all = listOf(
        Recipe("Gallo pinto con huevo", setOf("desayuno", "vegetariano", "sin_gluten", "economico"), listOf(Triple("arroz", 60.0, "g"), Triple("frijoles", 60.0, "g"), Triple("huevo", 1.0, "u"), Triple("cebolla", 0.25, "u"), Triple("chile dulce", 0.25, "u"))),
        Recipe("Avena con banano", setOf("desayuno", "vegetariano", "economico"), listOf(Triple("avena", 50.0, "g"), Triple("leche", 200.0, "ml"), Triple("banano", 1.0, "u"))),
        Recipe("Huevos revueltos con tostadas", setOf("desayuno", "vegetariano", "economico"), listOf(Triple("huevo", 2.0, "u"), Triple("pan", 2.0, "rebanadas"), Triple("tomate", 0.5, "u"))),
        Recipe("Casado con pollo", setOf("almuerzo", "sin_gluten"), listOf(Triple("arroz", 80.0, "g"), Triple("frijoles", 60.0, "g"), Triple("pollo", 150.0, "g"), Triple("plátano maduro", 0.5, "u"), Triple("repollo", 50.0, "g"), Triple("tomate", 0.5, "u"))),
        Recipe("Arroz con pollo", setOf("almuerzo", "sin_gluten", "economico"), listOf(Triple("arroz", 90.0, "g"), Triple("pollo", 120.0, "g"), Triple("zanahoria", 0.5, "u"), Triple("chile dulce", 0.25, "u"), Triple("petit pois", 30.0, "g"))),
        Recipe("Olla de carne", setOf("almuerzo", "sin_gluten"), listOf(Triple("carne de res", 150.0, "g"), Triple("papa", 1.0, "u"), Triple("yuca", 100.0, "g"), Triple("elote", 0.5, "u"), Triple("zanahoria", 0.5, "u"), Triple("chayote", 0.5, "u"))),
        Recipe("Sopa negra", setOf("cena", "vegetariano", "sin_gluten", "economico"), listOf(Triple("frijoles", 100.0, "g"), Triple("huevo", 1.0, "u"), Triple("cebolla", 0.25, "u"), Triple("culantro", 5.0, "g"))),
        Recipe("Picadillo de papa con tortillas", setOf("almuerzo", "economico"), listOf(Triple("papa", 2.0, "u"), Triple("carne molida", 80.0, "g"), Triple("cebolla", 0.25, "u"), Triple("tortillas de maíz", 2.0, "u"))),
        Recipe("Pasta con tomate y vegetales", setOf("cena", "vegetariano", "economico"), listOf(Triple("pasta", 100.0, "g"), Triple("tomate", 2.0, "u"), Triple("zucchini", 0.5, "u"), Triple("queso", 30.0, "g"))),
        Recipe("Tortillas con queso y frijoles", setOf("cena", "vegetariano", "economico"), listOf(Triple("tortillas de maíz", 3.0, "u"), Triple("queso", 60.0, "g"), Triple("frijoles", 60.0, "g"))),
        Recipe("Pescado al ajillo con arroz", setOf("almuerzo", "sin_gluten"), listOf(Triple("filete de pescado", 150.0, "g"), Triple("ajo", 2.0, "dientes"), Triple("arroz", 80.0, "g"), Triple("lechuga", 60.0, "g"))),
        Recipe("Lentejas con arroz", setOf("almuerzo", "vegetariano", "vegano", "sin_gluten", "economico"), listOf(Triple("lentejas", 80.0, "g"), Triple("arroz", 70.0, "g"), Triple("zanahoria", 0.5, "u"), Triple("cebolla", 0.25, "u"))),
        Recipe("Arroz con atún", setOf("cena", "sin_gluten", "economico"), listOf(Triple("arroz", 80.0, "g"), Triple("atún", 0.5, "latas"), Triple("chile dulce", 0.25, "u"), Triple("maíz dulce", 30.0, "g"))),
        Recipe("Pollo a la plancha con vegetales", setOf("cena", "sin_gluten"), listOf(Triple("pechuga de pollo", 150.0, "g"), Triple("brócoli", 80.0, "g"), Triple("zanahoria", 0.5, "u"), Triple("papa", 1.0, "u"))),
        Recipe("Burritos de frijol", setOf("cena", "vegetariano", "economico"), listOf(Triple("tortillas de harina", 2.0, "u"), Triple("frijoles", 80.0, "g"), Triple("queso", 40.0, "g"))),
        Recipe("Ensalada de garbanzos", setOf("almuerzo", "vegetariano", "vegano", "sin_gluten"), listOf(Triple("garbanzos", 100.0, "g"), Triple("tomate", 1.0, "u"), Triple("pepino", 0.5, "u"), Triple("limón", 0.5, "u"))),
        Recipe("Gallo pinto vegano", setOf("desayuno", "vegetariano", "vegano", "sin_gluten", "economico"), listOf(Triple("arroz", 60.0, "g"), Triple("frijoles", 70.0, "g"), Triple("cebolla", 0.25, "u"), Triple("chile dulce", 0.25, "u"), Triple("aguacate", 0.5, "u"))),
    )

    private val notVegetarian = listOf("pollo", "carne", "pescado", "atún")
    private val notVegan = notVegetarian + listOf("huevo", "queso", "leche")
    private val gluten = listOf("pasta", "pan", "tortillas de harina", "avena")

    fun allowed(r: Recipe, prefs: Set<String>): Boolean {
        val ing = r.ingredients.map { it.first }
        if ("vegano" in prefs && ing.any { i -> notVegan.any { i.contains(it) } }) return false
        if ("vegetariano" in prefs && ing.any { i -> notVegetarian.any { i.contains(it) } }) return false
        if ("sin_gluten" in prefs && ing.any { i -> gluten.any { i.contains(it) } }) return false
        return true
    }
}

class MealAndGroceryPlannerSkill : Skill {
    override val id = "meal_and_grocery_planner"

    override fun parseText(text: String): JsonObject? {
        val f = TextNormalizer.canonical(text)
        return when {
            SkillText.containsAny(f, "lista del super", "lista de compras", "lista del supermercado") && !SkillText.containsAny(f, "agrega", "anade", "pon ") -> jsonOf("action" to "list_groceries")
            Regex("^(agrega|anade|pon) (.+) (a|en) la lista( de compras| del super(mercado)?)?$").find(f)?.let { true } == true -> jsonOf("action" to "add_item", "item" to Regex("^(agrega|anade|pon) (.+) (a|en) la lista").find(f)!!.groupValues[2])
            else -> null
        }
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val plans = ctx.mealPlans(); val groceries = ctx.groceries()
        val today = ctx.time.zonedNow().toLocalDate()
        return when (input.str("action")) {
            "plan_week" -> {
                val prefs = input.strings("preferences").map { TextNormalizer.fold(it).replace(' ', '_').replace("-", "_") }.toSet()
                val people = input.int("people") ?: 2
                val meals = input.strings("meals_per_day").ifEmpty { listOf("almuerzo", "cena") }
                val custom = input.strings("meals")
                val pool = Recipes.all.filter { Recipes.allowed(it, prefs) }.let { l -> if ("economico" in prefs) l.sortedByDescending { "economico" in it.tags } else l }
                if (pool.isEmpty() && custom.isEmpty()) return Outcome.fail("no_recipes", "No tengo recetas que cumplan esas preferencias.", "Indica tus propios platos en «meals».")
                val days = (0 until 7).map { d ->
                    val date = today.plusDays(d.toLong())
                    MealDay(date.toString(), meals.mapIndexed { mi, slot ->
                        custom.getOrNull((d * meals.size + mi) % custom.size.coerceAtLeast(1))?.takeIf { custom.isNotEmpty() }
                            ?: pool.filter { slot in it.tags }.ifEmpty { pool }.let { it[(d * 3 + mi) % it.size].name }
                    })
                }
                val plan = MealPlan(today.toString(), days, people)
                plans.save(today.toString(), plan)
                Outcome.done("Plan de 7 días para $people personas (${meals.joinToString(" y ")})" + (if (prefs.isNotEmpty()) " respetando: ${prefs.joinToString()}" else "") + ". Pide «lista de compras» para generar lo que hay que comprar.",
                    jsonOf("action" to "plan_week", "plan" to mapOf("week" to plan.week, "people" to people, "days" to days.map { mapOf("day" to it.day, "meals" to it.meals) })),
                    cards = listOf(ResultCard.Info("Menú semanal", days.map { "${it.day}: ${it.meals.joinToString(" / ")}" })))
            }
            "grocery_list" -> {
                val plan = plans.all().maxByOrNull { it.first }?.second ?: return Outcome.fail("no_plan", "No hay un plan de comidas.", "Pide primero «planifica las comidas de la semana».")
                val totals = linkedMapOf<Pair<String, String>, Double>()
                val forMeals = mutableMapOf<String, MutableSet<String>>()
                val unknown = mutableListOf<String>()
                for (d in plan.days) for (meal in d.meals) {
                    val r = Recipes.all.firstOrNull { it.name == meal } ?: run { unknown += meal; null } ?: continue
                    r.ingredients.forEach { (name, qty, unit) -> totals[name to unit] = (totals[name to unit] ?: 0.0) + qty * plan.people; forMeals.getOrPut(name) { mutableSetOf() } += meal }
                }
                val existing = groceries.all()
                val records = totals.map { (k, q) ->
                    val (name, unit) = k
                    val qty = when (unit) { "g" -> if (q >= 1000) "${fmt(Tabular.round(q / 1000))} kg" else "${fmt(Math.ceil(q))} g"; "ml" -> if (q >= 1000) "${fmt(Tabular.round(q / 1000))} l" else "${fmt(q)} ml"; else -> "${fmt(Math.ceil(q))} $unit" }
                    val id = existing.firstOrNull { TextNormalizer.fold(it.second.name) == TextNormalizer.fold(name) && !it.second.checked }?.first
                    (id ?: "") to GroceryItem(name, qty, false, forMeals[name].orEmpty().toList())
                }
                var next = groceries.nextId().toLong()
                ctx.services.store.putAll(records.map { (id, g) -> groceries.record(id.ifEmpty { (next++).toString() }, g) })
                val warn = if (unknown.isNotEmpty()) listOf("Sin receta para: ${unknown.distinct().joinToString()} (añade sus ingredientes a mano).") else emptyList()
                Outcome.done("Lista de compras con ${records.size} productos para ${plan.people} personas.", jsonOf("action" to "grocery_list", "items" to records.map { mapOf("name" to it.second.name, "quantity" to it.second.quantity) }), warnings = warn,
                    cards = listOf(ResultCard.Info("Lista de compras", records.map { "☐ ${it.second.name} — ${it.second.quantity}" })))
            }
            "add_item" -> {
                val item = input.str("item")?.trim()?.takeIf { it.length >= 2 } ?: return Outcome.invalid("Indica el producto.")
                val id = groceries.nextId()
                groceries.save(id, GroceryItem(item, input.str("quantity") ?: ""))
                Outcome.done("Agregué \"$item\" a la lista de compras.", jsonOf("action" to "add_item", "items" to listOf(mapOf("id" to id, "name" to item))))
            }
            "check_item" -> {
                val all = groceries.all()
                val match = input.str("item_id")?.let { id -> all.firstOrNull { it.first == id } } ?: input.str("item")?.let { n -> all.firstOrNull { TextNormalizer.fold(it.second.name).contains(TextNormalizer.fold(n)) && !it.second.checked } }
                    ?: return Outcome.fail("not_found", "No encontré ese producto en la lista.", "Pide la lista de compras.")
                groceries.save(match.first, match.second.copy(checked = true))
                Outcome.done("Marqué \"${match.second.name}\" como comprado.", jsonOf("action" to "check_item", "items" to listOf(mapOf("id" to match.first, "name" to match.second.name))))
            }
            "list_groceries" -> {
                val all = groceries.all()
                val pending = all.filter { !it.second.checked }
                Outcome.done(if (pending.isEmpty()) "La lista de compras está vacía." else "Faltan ${pending.size} productos: " + pending.take(8).joinToString { it.second.name } + if (pending.size > 8) "…" else ".",
                    jsonOf("action" to "list_groceries", "items" to all.map { (id, g) -> mapOf("id" to id, "name" to g.name, "quantity" to g.quantity, "checked" to g.checked) }),
                    cards = listOf(ResultCard.Info("Lista de compras", all.map { (_, g) -> (if (g.checked) "☑ " else "☐ ") + g.name + if (g.quantity.isNotBlank()) " — ${g.quantity}" else "" })))
            }
            "clear_checked" -> {
                val checked = groceries.all().filter { it.second.checked }
                checked.forEach { groceries.delete(it.first) }
                Outcome.done("Quité ${checked.size} productos ya comprados.", jsonOf("action" to "clear_checked", "items" to checked.map { mapOf("name" to it.second.name) }))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}
