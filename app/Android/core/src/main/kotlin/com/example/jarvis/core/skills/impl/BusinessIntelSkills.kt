package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.business.ProspectStatus
import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.double
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.long
import com.example.jarvis.core.json.str
import com.example.jarvis.core.json.strings
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.text.TextNormalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

// ============================================================ customer_relationships

class CustomerRelationshipsSkill : Skill {
    override val id = "customer_relationships"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val src = listOf(SkillSource("Prospectos, contactos e interacciones del teléfono", kind = "local"))
        val prospects = ctx.services.prospects.list()
        fun findProspect(): com.example.jarvis.core.business.Prospect? =
            input.long("prospect_id")?.let { id -> prospects.firstOrNull { it.id == id } }
                ?: input.str("contact_name")?.let { n -> val f = TextNormalizer.fold(n); prospects.firstOrNull { TextNormalizer.fold(it.name) == f } ?: prospects.filter { TextNormalizer.fold(it.name).contains(f) }.singleOrNull() }
        return when (input.str("action")) {
            "log_interaction" -> {
                val summary = input.str("summary") ?: return Outcome.invalid("Falta el resumen de la interacción.")
                val p = findProspect()
                val name = p?.name ?: input.str("contact_name") ?: return Outcome.invalid("Indica el contacto (prospect_id o contact_name).")
                val warnings = mutableListOf<String>()
                var taskId: Long? = null
                input.str("follow_up_when")?.let { whenText ->
                    val r = ctx.tool("create_task", jsonOf("title" to "Seguimiento: $name", "description" to (input.str("follow_up") ?: summary), "due" to whenText, "remind" to true))
                    if (r.ok) taskId = (r.data as? JsonObject)?.long("task_id") else warnings += "No se creó el seguimiento: ${r.message}"
                }
                val col = ctx.interactions()
                val id = col.nextId()
                col.save(id, Interaction(p?.id, name, input.str("kind") ?: "nota", summary, ctx.nowIso(), input.str("follow_up") ?: input.str("follow_up_when"), taskId))
                p?.let { if (input.str("status") != null || it.status == ProspectStatus.NUEVO) ctx.tool("update_prospect", jsonOf("prospect_id" to it.id, "status" to (input.str("status") ?: ProspectStatus.CONTACTADO.name), "next_action" to (input.str("follow_up") ?: it.nextAction))) }
                Outcome.done("Registré ${input.str("kind") ?: "la nota"} con $name." + (taskId?.let { " Seguimiento programado (tarea $it)." } ?: "") + (if (p == null) " (No es un prospecto guardado: quedó como contacto libre.)" else ""),
                    jsonOf("action" to "log_interaction", "interaction" to mapOf("id" to id, "contact" to name, "prospect_id" to p?.id, "follow_up_task_id" to taskId)), src, warnings)
            }
            "history" -> {
                val p = findProspect()
                val name = p?.name ?: input.str("contact_name") ?: return Outcome.invalid("Indica el contacto.")
                val list = ctx.interactions().values().filter { (p != null && it.prospectId == p.id) || TextNormalizer.fold(it.contactName) == TextNormalizer.fold(name) }.sortedByDescending { it.date }
                Outcome.done(if (list.isEmpty()) "No hay interacciones registradas con $name." else "${list.size} interacciones con $name; la última: ${list.first().date.take(10)} (${list.first().kind}) — ${list.first().summary}.",
                    jsonOf("action" to "history", "contact" to name, "status" to p?.status?.label, "interactions" to list.map { mapOf("date" to it.date, "kind" to it.kind, "summary" to it.summary, "follow_up" to it.followUp) }), src,
                    cards = listOf(ResultCard.Info("Historial: $name", list.map { "${it.date.take(10)} · ${it.kind}: ${it.summary}" })))
            }
            "follow_ups" -> {
                val days = (input.int("days") ?: 14).toLong()
                val inter = ctx.interactions().values()
                val now = ctx.time.now()
                val stale = prospects.filter { it.status !in setOf(ProspectStatus.CLIENTE, ProspectStatus.DESCARTADO) }.mapNotNull { p ->
                    val last = inter.filter { it.prospectId == p.id }.maxOfOrNull { it.date }?.let { Instant.parse(it) } ?: p.updatedAt
                    val idle = Duration.between(last, now).toDays()
                    if (idle >= days) mapOf("prospect_id" to p.id, "name" to p.name, "status" to p.status.label, "days_without_contact" to idle, "phone" to p.phone) else null
                }.sortedByDescending { it["days_without_contact"] as Long }
                val pending = inter.filter { it.followUp != null && it.followUpTaskId != null }.mapNotNull { i -> ctx.services.tasks.list(com.example.jarvis.core.tasks.TaskFilter.PENDING).firstOrNull { it.id == i.followUpTaskId }?.let { t -> mapOf("contact" to i.contactName, "task_id" to t.id, "due" to t.due?.toString(), "what" to i.followUp) } }
                Outcome.done("${stale.size} contactos llevan $days días o más sin contacto; ${pending.size} seguimientos pendientes.", jsonOf("action" to "follow_ups", "stale" to stale, "pending" to pending), src,
                    cards = listOf(ResultCard.Info("Seguimientos", stale.map { "${it["name"]}: ${it["days_without_contact"]} días" } + pending.map { "Pendiente con ${it["contact"]}: ${it["what"]}" })))
            }
            "set_status" -> {
                val p = findProspect() ?: return Outcome.fail("not_found", "No encontré ese prospecto.", "Usa el id o el nombre exacto.")
                val r = ctx.tool("update_prospect", jsonOf("prospect_id" to p.id, "status" to (input.str("status") ?: return Outcome.invalid("Falta el estado.")), "next_action" to input.str("follow_up")))
                if (!r.ok) Outcome.fromTool(r, "Actualizar el prospecto") else Outcome.done(r.message, jsonOf("action" to "set_status", "prospect_id" to p.id), src)
            }
            "contacts" -> {
                val q = input.str("query")?.let { TextNormalizer.fold(it) }
                val inter = ctx.interactions().values()
                val rows = prospects.filter { q == null || TextNormalizer.fold(it.name + " " + (it.canton ?: "")).contains(q) }.map { p ->
                    mapOf("type" to "prospecto", "prospect_id" to p.id, "name" to p.name, "status" to p.status.label, "phone" to p.phone, "last_contact" to inter.filter { it.prospectId == p.id }.maxOfOrNull { it.date })
                } + ctx.contacts().all().filter { (_, c) -> q == null || TextNormalizer.fold(c.name + " " + (c.company ?: "")).contains(q) }.map { (id, c) ->
                    mapOf("type" to "contacto", "contact_id" to id, "name" to c.name, "company" to c.company, "phone" to c.phone, "email" to c.email, "verified" to c.verified)
                }
                Outcome.done("${rows.size} contactos.", jsonOf("action" to "contacts", "contacts" to rows), src)
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ negotiation_coach

class NegotiationCoachSkill : Skill {
    override val id = "negotiation_coach"

    private fun r2(d: Double) = BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).toDouble()

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val role = input.str("role") ?: "seller"
        val seller = role == "seller"
        val cur = input.str("currency") ?: "CRC"
        fun m(d: Double) = Money.format(BigDecimal.valueOf(d), cur)
        val warnings = mutableListOf<String>()
        return when (input.str("action")) {
            "plan" -> {
                val target = input.double("target") ?: return Outcome.invalid("Indica tu objetivo (target).")
                val walk = input.double("walk_away") ?: return Outcome.invalid("Indica tu límite (walk_away): el peor acuerdo que aceptarías.")
                if (seller && walk > target || !seller && walk < target) return Outcome.invalid(if (seller) "Como vendedor, tu límite debe ser menor o igual que tu objetivo." else "Como comprador, tu límite debe ser mayor o igual que tu objetivo.")
                val opening = input.double("opening") ?: r2(if (seller) target * 1.12 else target * 0.88)
                val gap = kotlin.math.abs(opening - target)
                val steps = listOf(0.5, 0.3, 0.2).runningFold(opening) { acc, f -> r2(if (seller) acc - gap * f else acc + gap * f) }.drop(1)
                val theirs = input.double("their_limit")
                val zopa = theirs?.let { t -> if (seller && t >= walk) mapOf("from" to walk, "to" to t) else if (!seller && t <= walk) mapOf("from" to t, "to" to walk) else null }
                if (theirs != null && zopa == null) warnings += "Con el límite estimado de la contraparte no hay zona de acuerdo: prepara tu alternativa (BATNA)."
                val tradeables = input.strings("issues").ifEmpty { listOf("plazo de pago", "volumen", "fecha de entrega", "garantía", "transporte") }
                val plan = listOf(
                    "Abre con ${m(opening)} y justifícalo con valor concreto (no con tu necesidad).",
                    "Concesiones decrecientes: ${steps.joinToString(" → ") { m(it) }}; cada concesión a cambio de algo.",
                    "Tu límite es ${m(walk)}: por ${if (seller) "debajo" else "encima"} de eso, retírate y usa tu alternativa.",
                    "Negocia también: ${tradeables.joinToString()}.",
                    "Haz preguntas abiertas y deja que la otra parte haga la primera concesión.",
                )
                Outcome.done("Plan: abrir en ${m(opening)}, objetivo ${m(target)}, límite ${m(walk)}.", jsonOf("action" to "plan", "role" to role, "opening" to opening, "target" to target, "walk_away" to walk, "concessions" to steps, "zopa" to zopa, "tradeables" to tradeables, "plan" to plan), warnings = warnings,
                    cards = listOf(ResultCard.Info("Plan de negociación", plan)))
            }
            "evaluate_offer" -> {
                val offer = input.double("offer") ?: return Outcome.invalid("Indica la oferta recibida.")
                val target = input.double("target") ?: return Outcome.invalid("Indica tu objetivo.")
                val walk = input.double("walk_away") ?: return Outcome.invalid("Indica tu límite.")
                val meetsTarget = if (seller) offer >= target else offer <= target
                val acceptable = if (seller) offer >= walk else offer <= walk
                val counter = r2((offer + target) / 2)
                val (decision, advice) = when {
                    meetsTarget -> "aceptar" to "La oferta alcanza tu objetivo. Acepta y confirma por escrito las condiciones."
                    acceptable -> "contraofertar" to "Está dentro de tu límite pero no en tu objetivo: contraoferta con ${m(counter)} y pide algo a cambio."
                    else -> "rechazar" to "Está fuera de tu límite. Rechaza con cortesía o explora otras variables (plazo, volumen) antes de ceder."
                }
                Outcome.done("Oferta de ${m(offer)}: $decision. $advice", jsonOf("action" to "evaluate_offer", "decision" to decision, "counter_offer" to if (decision == "contraofertar") counter else null, "advice" to advice))
            }
            "practice" -> {
                val tactics = listOf(
                    "«Es mi última oferta»" to "Pregunta qué necesitaría para mejorarla y ofrece una variable distinta (plazo, volumen).",
                    "Ancla extrema" to "No contraofertes de inmediato: pide la justificación y reancla con tus datos.",
                    "«Su competencia lo da más barato»" to "Pide detalles comparables; resalta tu diferencia de valor sin desacreditar.",
                    "Silencio prolongado" to "Mantén la calma; no rellenes el silencio con concesiones.",
                    "Presión de tiempo" to "Recuerda tu límite; las decisiones bajo presión suelen costar.",
                )
                val ai = ctx.tryModel("Haz de contraparte ${if (seller) "compradora" else "vendedora"} en una negociación. Contexto: ${input.str("context") ?: "sin contexto"}. Escribe tu primera objeción y 2 respuestas recomendadas para el usuario.", input.str("context") ?: "", warnings, "La práctica con IA", AssistantMode.BALANCED)
                Outcome.done("Tácticas frecuentes y cómo responder" + (if (ai != null) ", y un ejercicio de práctica." else "."), jsonOf("action" to "practice", "tactics" to tactics.map { mapOf("tactic" to it.first, "response" to it.second) }, "roleplay" to ai, "used_model" to (ai != null)), warnings = warnings,
                    cards = listOf(ResultCard.Info("Tácticas de negociación", tactics.map { "${it.first}: ${it.second}" } + listOfNotNull(ai))))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ contract_review

class ContractReviewSkill : Skill {
    override val id = "contract_review"

    data class Clause(val key: String, val label: String, val patterns: List<String>)

    private val clauses = listOf(
        Clause("term", "Plazo o vigencia", listOf("plazo", "vigencia", "duracion", "tendra una duracion")),
        Clause("renewal", "Renovación", listOf("renovara automaticamente", "renovacion automatica", "prorroga automatica", "se prorrogara")),
        Clause("termination", "Terminación", listOf("rescision", "rescindir", "terminacion anticipada", "dar por terminado", "resolucion del contrato", "preaviso")),
        Clause("penalty", "Penalidades", listOf("penalidad", "multa", "clausula penal", "indemnizara", "dano y perjuicio")),
        Clause("payment", "Pagos", listOf("pago", "precio", "honorarios", "factura", "interes moratorio", "intereses")),
        Clause("confidentiality", "Confidencialidad", listOf("confidencial", "confidencialidad", "secreto")),
        Clause("exclusivity", "Exclusividad", listOf("exclusividad", "exclusiva", "en forma exclusiva")),
        Clause("non_compete", "No competencia", listOf("no competencia", "no competir", "abstenerse de competir")),
        Clause("ip", "Propiedad intelectual", listOf("propiedad intelectual", "derechos de autor", "titularidad")),
        Clause("liability", "Responsabilidad", listOf("responsabilidad", "limitacion de responsabilidad", "eximira")),
        Clause("warranty", "Garantía", listOf("garantia", "garantiza")),
        Clause("jurisdiction", "Jurisdicción / ley aplicable", listOf("jurisdiccion", "tribunales", "ley aplicable", "leyes de", "arbitraje", "conciliacion")),
        Clause("assignment", "Cesión", listOf("ceder", "cesion")),
        Clause("force_majeure", "Fuerza mayor", listOf("fuerza mayor", "caso fortuito")),
        Clause("data", "Datos personales", listOf("datos personales", "ley 8968", "proteccion de la persona frente al tratamiento")),
        Clause("modification", "Modificaciones", listOf("modificar", "modificacion", "enmienda")),
    )

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val text = when (input.str("source")) {
            "file" -> {
                val (file, stop) = ctx.pickFile(DocumentExtractor.supportedMimeTypes + "application/pdf")
                if (stop != null) return stop
                try { DocumentExtractor.extract(file!!.name, file.mimeType, file.bytes).text } catch (e: UnsupportedDocumentException) { return Outcome.fail("unsupported_format", e.message ?: "Formato no soportado.", "Usa DOCX, ODT o texto.") }
            }
            else -> input.str("text") ?: return Outcome.invalid("Incluye el texto del contrato.")
        }
        val sentences = SkillText.sentences(text.replace('\n', ' '))
        val found = clauses.associateWith { c -> sentences.filter { s -> val f = TextNormalizer.fold(s); c.patterns.any { f.contains(it) } } }
        val risks = mutableListOf<Map<String, String>>()
        fun risk(level: String, msg: String, ex: String?) { risks += mapOf("level" to level, "message" to msg, "excerpt" to (ex?.let { SkillText.truncate(it, 220) } ?: "")) }
        found[clauses.first { it.key == "renewal" }]?.firstOrNull()?.let { risk("medio", "Renovación automática: anota la fecha límite para avisar si no quieres renovar.", it) }
        found[clauses.first { it.key == "penalty" }]?.forEach { s -> risk(if (Regex("\\d").containsMatchIn(s)) "alto" else "medio", "Penalidad o multa: verifica el monto y cuándo aplica.", s) }
        found[clauses.first { it.key == "exclusivity" }]?.firstOrNull()?.let { risk("medio", "Exclusividad: limita con quién más puedes trabajar.", it) }
        found[clauses.first { it.key == "non_compete" }]?.firstOrNull()?.let { risk("alto", "No competencia: revisa duración, territorio y si es razonable.", it) }
        sentences.firstOrNull { TextNormalizer.fold(it).contains("unilateral") }?.let { risk("alto", "Facultad unilateral: una parte puede cambiar o terminar sin acuerdo.", it) }
        sentences.firstOrNull { val f = TextNormalizer.fold(it); f.contains("responsabilidad") && (f.contains("ilimitad") || f.contains("total")) }?.let { risk("alto", "Responsabilidad ilimitada o total.", it) }
        found[clauses.first { it.key == "jurisdiction" }]?.firstOrNull { s -> val f = TextNormalizer.fold(s); listOf("nueva york", "florida", "espana", "mexico", "panama", "delaware", "extranjer").any { f.contains(it) } }?.let { risk("medio", "Jurisdicción fuera de Costa Rica: un conflicto sería más caro de resolver.", it) }
        sentences.mapNotNull { s -> Regex("(\\d{1,3}(?:[.,]\\d+)?)\\s?%").findAll(s).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }.firstOrNull { it >= 3.0 }?.let { s to it } }
            .firstOrNull { (s, _) -> TextNormalizer.fold(s).contains("interes") }?.let { (s, p) -> risk("medio", "Intereses de $p %: confirma si es mensual o anual.", s) }
        Regex("(?i)preaviso de (\\d+) (dias|meses)").find(TextNormalizer.fold(text))?.let { m -> val n = m.groupValues[1].toInt(); if (m.groupValues[2] == "meses" && n >= 3 || m.groupValues[2] == "dias" && n >= 90) risk("medio", "Preaviso largo (${m.value}).", m.value) }
        val missing = listOf("termination", "jurisdiction", "payment", "term").map { k -> clauses.first { it.key == k } }.filter { found[it].isNullOrEmpty() }.map { "No encontré cláusula de ${it.label.lowercase()}." }
        val amounts = Money.findAll(text).take(10).map { it.format() }
        val dates = Regex("(?i)\\b\\d{1,2} de (enero|febrero|marzo|abril|mayo|junio|julio|agosto|setiembre|septiembre|octubre|noviembre|diciembre)(?: de(?:l)? \\d{4})?\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b").findAll(text).map { it.value }.distinct().take(10).toList()
        val warnings = mutableListOf("Revisión automática orientativa: no sustituye la asesoría de un abogado.")
        val ai = if (input.bool("use_ai") == true) ctx.tryModel(
            "Explica este contrato en lenguaje sencillo para la parte \"${input.str("party") ?: "el usuario"}\": obligaciones, riesgos y 5 preguntas para negociar. No des asesoría legal definitiva." + (input.str("question")?.let { " Pregunta: $it" } ?: ""),
            text, warnings, "El análisis con IA", AssistantMode.DEEP,
        ) else null
        val data = jsonOf(
            "clauses" to found.filter { it.value.isNotEmpty() }.map { (c, s) -> mapOf("key" to c.key, "label" to c.label, "excerpts" to s.take(2).map { SkillText.truncate(it, 220) }) },
            "risks" to risks, "missing" to missing, "amounts" to amounts, "dates" to dates, "ai_analysis" to ai, "used_model" to (ai != null), "words" to SkillText.words(text).size,
        )
        val high = risks.count { it["level"] == "alto" }
        val msg = "Revisé el contrato: ${found.count { it.value.isNotEmpty() }} tipos de cláusula, ${risks.size} puntos de atención" + (if (high > 0) " ($high altos)" else "") + (if (missing.isNotEmpty()) " y ${missing.size} cláusulas importantes ausentes" else "") + "."
        return Outcome.done(msg, data, warnings = warnings, cards = listOf(ResultCard.Info("Revisión de contrato", risks.map { "[${it["level"]}] ${it["message"]}" } + missing + listOfNotNull(ai))))
    }
}

// ============================================================ competitor_intelligence

class CompetitorIntelligenceSkill : Skill {
    override val id = "competitor_intelligence"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        ctx.requireServer("El análisis de competencia")?.let { return it }
        val category = input.str("category")!!
        val location = input.str("location") ?: return Outcome.invalid("Indica la zona (location).")
        val r = ctx.tool("business_search", jsonOf("category" to category, "location" to location, "limit" to 50))
        if (!r.ok) return Outcome.fromTool(r, "La búsqueda de competidores")
        val d = r.data as JsonObject
        val own = input.str("own_business_name")?.let { TextNormalizer.fold(it) }
        val list = d.arr("results")?.map { it.jsonObject }.orEmpty().filter { o -> own == null || !TextNormalizer.fold(o.str("name").orEmpty()).contains(own) }
        fun has(o: JsonObject, k: String) = o.str(k)?.let { it != "no disponible" } ?: false
        val n = list.size.coerceAtLeast(1)
        val pct = mapOf("phone" to list.count { has(it, "phone") } * 100 / n, "website" to list.count { has(it, "website") } * 100 / n, "email" to list.count { has(it, "email") } * 100 / n)
        val byZone = list.groupBy { it.str("location")?.substringBefore(',')?.takeIf { z -> z != "no disponible" } ?: "sin dato" }.mapValues { it.value.size }.entries.sortedByDescending { it.value }
        val warnings = mutableListOf<String>()
        val webProfiles = mutableListOf<Map<String, Any?>>()
        for (o in list.filter { has(it, "website") }.take(input.int("read_websites") ?: 3)) {
            val (text, rr) = ctx.read(o.str("website")!!)
            if (text == null) { warnings += "No se pudo leer el sitio de ${o.str("name")}: ${rr.message}"; continue }
            webProfiles += mapOf("name" to o.str("name"), "url" to o.str("website"), "key_terms" to SkillText.keyTerms(text, 8), "prices" to Money.findAll(text).take(5).map { it.format() })
        }
        val insights = mutableListOf<String>()
        if (pct.getValue("website") < 50) insights += "Solo ${pct["website"]} % tiene sitio web registrado: una buena presencia en línea te diferencia."
        if (pct.getValue("phone") < 60) insights += "${100 - pct.getValue("phone")} % no tiene teléfono publicado en OpenStreetMap."
        byZone.firstOrNull()?.let { insights += "Mayor concentración: ${it.key} (${it.value})." }
        if (list.size < 3) insights += "Pocos competidores registrados en OpenStreetMap; la cobertura puede ser incompleta."
        val data = jsonOf("category" to d.str("category"), "area" to d.str("area"), "competitors" to list.size, "with_data_percent" to pct, "by_zone" to byZone.map { mapOf("zone" to it.key, "count" to it.value) },
            "web_profiles" to webProfiles, "insights" to insights, "list" to list.take(30).map { mapOf("name" to it.str("name"), "location" to it.str("location"), "phone" to it.str("phone"), "website" to it.str("website")) })
        return Outcome.done("${list.size} competidores de ${d.str("category") ?: category} en ${d.str("area") ?: location}. " + insights.firstOrNull().orEmpty(), data,
            listOf(SkillSource("OpenStreetMap", "https://www.openstreetmap.org", "osm (ODbL)", d.str("retrieved_at"))) + webProfiles.map { SkillSource(it["name"] as String? ?: "Sitio", it["url"] as String?, "web") }, warnings + "Datos de OpenStreetMap: puede haber negocios sin registrar.",
            listOf(ResultCard.Info("Competencia: $category en $location", insights + byZone.take(5).map { "${it.key}: ${it.value}" })))
    }
}

// ============================================================ supplier_verifier

class SupplierVerifierSkill : Skill {
    override val id = "supplier_verifier"

    private val freeMail = setOf("gmail.com", "hotmail.com", "outlook.com", "yahoo.com", "live.com", "icloud.com", "protonmail.com", "qq.com", "163.com", "126.com")
    private val countryCodes = mapOf("+506" to "Costa Rica", "+86" to "China", "+1" to "Estados Unidos/Canadá", "+52" to "México", "+57" to "Colombia", "+34" to "España", "+507" to "Panamá", "+91" to "India", "+49" to "Alemania", "+55" to "Brasil")

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val name = input.str("name")!!
        val signals = mutableListOf<Map<String, String>>()
        fun sig(kind: String, msg: String, evidence: String = "") { signals += mapOf("kind" to kind, "message" to msg, "evidence" to evidence) }
        val website = input.str("website")
        val webHost = website?.let { Web.host(it).lowercase() }
        input.str("email")?.let { e ->
            val domain = e.substringAfter('@').lowercase()
            when {
                domain in freeMail -> sig("negativa", "Usa correo gratuito ($domain) en lugar de uno con el dominio de la empresa.", e)
                webHost != null && (domain == webHost || webHost.endsWith(".$domain") || domain.endsWith(webHost)) -> sig("positiva", "El correo usa el mismo dominio que el sitio web.", e)
                webHost != null -> sig("negativa", "El dominio del correo ($domain) no coincide con el del sitio ($webHost).", e)
                else -> sig("neutral", "Correo con dominio propio ($domain).", e)
            }
        }
        input.str("phone")?.let { p ->
            val clean = p.filter { it.isDigit() || it == '+' }
            val code = countryCodes.keys.sortedByDescending { it.length }.firstOrNull { clean.startsWith(it) }
            val claimed = input.str("country")
            when {
                code == null -> sig("neutral", "El teléfono no indica el código de país.", p)
                claimed != null && !TextNormalizer.fold(countryCodes.getValue(code)).contains(TextNormalizer.fold(claimed)) -> sig("negativa", "El código ${code} (${countryCodes[code]}) no coincide con el país declarado ($claimed).", p)
                else -> sig("positiva", "Código de país ${code} coherente (${countryCodes[code]}).", p)
            }
        }
        input.str("tax_id")?.let { id ->
            val digits = id.filter { it.isDigit() }
            if (TextNormalizer.fold(input.str("country") ?: "costa rica").contains("costa rica")) {
                if (Regex("^3\\d{9}$").matches(digits)) sig("neutral", "Formato de cédula jurídica válido (solo formato). Verifícala en el Registro Nacional o en Hacienda (ATV).", id)
                else sig("negativa", "El identificador no tiene formato de cédula jurídica costarricense (3-XXX-XXXXXX).", id)
            }
        }
        val warnings = mutableListOf<String>()
        val sources = mutableListOf<SkillSource>()
        if (!ctx.env.serverConfigured()) {
            warnings += "Sin servidor no puedo revisar el sitio web ni buscar reseñas: solo comprobé formatos y coherencia."
        } else {
            if (website != null) {
                val (text, r) = ctx.read(website)
                if (text == null) sig("negativa", "El sitio web no se pudo leer: ${r.message}", website)
                else {
                    sources += SkillSource("Sitio del proveedor", website, "web")
                    val d = r.data as JsonObject
                    val contacts = d["contacts"] as? JsonObject
                    val phones = contacts?.arr("phones")?.map { it.jsonPrimitive.content }.orEmpty()
                    val emails = contacts?.arr("emails")?.map { it.jsonPrimitive.content }.orEmpty()
                    input.str("email")?.let { e -> if (emails.any { it.equals(e, true) }) sig("positiva", "El correo aparece publicado en su sitio web.", e) else if (emails.isNotEmpty()) sig("neutral", "Su sitio publica otros correos: ${emails.take(3).joinToString()}.") }
                    input.str("phone")?.let { p -> val pd = p.filter { it.isDigit() }.takeLast(8); if (phones.any { it.filter { c -> c.isDigit() }.endsWith(pd) }) sig("positiva", "El teléfono aparece en su sitio web.", p) }
                    if (TextNormalizer.fold(text).contains(TextNormalizer.fold(name))) sig("positiva", "El nombre de la empresa aparece en el sitio.") else sig("neutral", "El nombre indicado no aparece tal cual en el sitio.")
                }
            }
            val (hits, r) = ctx.search("\"$name\" estafa OR fraude OR scam OR reviews", 8)
            if (r.ok) {
                val bad = hits.filter { h -> SkillText.containsAny(h.title + " " + h.snippet, "estafa", "fraude", "scam", "denuncia", "fake", "no entregaron") }
                bad.take(3).forEach { h -> sig("negativa", "Resultado web con menciones negativas (revísalo; puede no referirse a este proveedor).", h.url) }
                if (bad.isEmpty() && hits.isNotEmpty()) sig("neutral", "La búsqueda de reseñas no mostró menciones de fraude en los primeros resultados.")
                sources += hits.take(5).map(Web::source)
            } else warnings += "Búsqueda de reseñas no disponible: ${r.message}"
        }
        val score = (50 + signals.sumOf { when (it["kind"]) { "positiva" -> 12; "negativa" -> -18; else -> 0 }.toInt() }).coerceIn(0, 100)
        val verdict = when { score >= 70 -> "señales mayormente positivas"; score >= 45 -> "señales mixtas: pide referencias y empieza con un pedido pequeño"; else -> "varias señales de alerta: no pagues por adelantado sin verificar más" }
        val recs = listOf("Pide factura y cédula jurídica y verifícalas en Hacienda (ATV) o el Registro Nacional.", "Solicita referencias de clientes en Costa Rica.", "Para importaciones: usa métodos de pago con protección (carta de crédito o garantía de la plataforma).", "Haz una videollamada y pide fotos actuales del producto o la planta.")
        val data = jsonOf("name" to name, "score" to score, "verdict" to verdict, "signals" to signals, "recommendations" to recs)
        val msg = "Verificación de $name: $score/100, $verdict."
        warnings += "Indicadores orientativos, no una verificación legal ni una garantía."
        return if (!ctx.env.serverConfigured()) Outcome.partial(msg, data, warnings, sources) else Outcome.done(msg, data, sources, warnings,
            listOf(ResultCard.Info("Proveedor: $name ($score/100)", signals.map { "[${it["kind"]}] ${it["message"]}" } + recs.take(2))))
    }
}
