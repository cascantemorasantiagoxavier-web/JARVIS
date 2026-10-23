package com.example.jarvis.core.skills.impl

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
import com.example.jarvis.core.text.TextNormalizer
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.LocalDate

/** Redacción por plantillas (sin IA) en español de Costa Rica. */
internal object Drafting {
    private val infinitive = Regex("^(\\p{L}+(ar|er|ir))(\\s|$)")

    fun body(purpose: String, details: String?, recipient: String?, tone: String, channel: String): String {
        val p = purpose.trim().trimEnd('.').replaceFirstChar { it.lowercase() }
        val formal = tone == "formal" || channel == "email" && tone != "cordial" && tone != "breve"
        val verb = if (formal) "Le escribo" else "Te escribo"
        val core = if (infinitive.containsMatchIn(TextNormalizer.fold(p))) "$verb para $p." else "$verb sobre $p."
        val extra = details?.trim()?.takeIf { it.isNotEmpty() }?.let { " " + it.replaceFirstChar { c -> c.uppercase() }.let { d -> if (d.endsWith('.')) d else "$d." } } ?: ""
        val name = recipient?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            tone == "breve" -> listOfNotNull(name?.let { "$it," }, "$core$extra".removePrefix("$verb para ").removePrefix("$verb sobre ").replaceFirstChar { it.uppercase() }).joinToString(" ")
            formal -> "${if (name != null) "Estimado/a $name:" else "Buen día:"}\n\n$core$extra\n\nQuedo atento a su respuesta.\n\nSaludos cordiales."
            else -> "Hola${name?.let { " $it" } ?: ""}, ${core.replaceFirstChar { it.lowercase() }}$extra\n\n¡Muchas gracias!"
        }
    }

    fun subject(purpose: String): String = purpose.trim().trimEnd('.').replaceFirstChar { it.uppercase() }.let { SkillText.truncate(it, 70) }

    /** Abre la app correspondiente (herramienta de riesgo externo → confirmación). */
    suspend fun open(ctx: SkillContext, channel: String, phone: String?, email: String?, subject: String?, text: String): com.example.jarvis.core.tools.ToolResult =
        when (channel) {
            "email" -> ctx.tool("compose_email", jsonOf("to" to listOfNotNull(email), "subject" to subject, "body" to text))
            "whatsapp", "sms" -> ctx.tool("compose_message", jsonOf("channel" to channel, "phone" to phone, "text" to text))
            else -> ctx.tool("share_text", jsonOf("text" to text, "title" to subject))
        }
}

// ============================================================ message_drafter

class MessageDrafterSkill : Skill {
    override val id = "message_drafter"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val purpose = input.str("purpose")!!
        val channel = input.str("channel") ?: "generic"
        val tone = input.str("tone") ?: "cordial"
        val recipient = input.str("recipient")
        val warnings = mutableListOf<String>()
        val ai = ctx.llm()?.let {
            ctx.tryModel("Redacta un mensaje de ${if (channel == "email") "correo" else channel} en tono $tone" + (recipient?.let { r -> " para $r" } ?: "") +
                ". Propósito: $purpose. Usa solo los datos dados; no inventes fechas, montos ni compromisos. Devuelve solo el mensaje.", input.str("details") ?: purpose, warnings, "La redacción con IA")
        }
        val text = ai ?: Drafting.body(purpose, input.str("details"), recipient, tone, channel)
        val subject = if (channel == "email") input.str("subject") ?: Drafting.subject(purpose) else null
        val drafts = ctx.drafts()
        val draftId = drafts.nextId()
        var draft = Draft(channel, recipient ?: input.str("phone") ?: input.str("email"), subject, text, ctx.nowIso())
        var opened = false
        if (input.bool("send") == true) {
            if (channel == "whatsapp" && input.str("phone") == null) return Outcome.invalid("Para abrir WhatsApp necesito el número (phone).")
            if (channel == "email" && input.str("email") == null) return Outcome.invalid("Para abrir el correo necesito la dirección (email).")
            val r = Drafting.open(ctx, channel, input.str("phone"), input.str("email"), subject, text)
            if (!r.ok) {
                drafts.save(draftId, draft)
                return if (r.errorCode == "not_confirmed") Outcome.notConfirmed("No abrí ninguna app: el envío no fue confirmado. El borrador quedó guardado (id $draftId).") else Outcome.fail("open_failed", r.message, "El borrador quedó guardado; puedes copiarlo.")
            }
            opened = true
            draft = draft.copy(status = "opened")
        }
        drafts.save(draftId, draft)
        val data = jsonOf("draft" to mapOf("id" to draftId, "channel" to channel, "recipient" to draft.recipient, "subject" to subject, "body" to text), "opened" to opened, "used_model" to (ai != null))
        val msg = if (opened) "Abrí la app con el mensaje listo; revísalo y envíalo tú." else "Borrador listo (id $draftId). No se envió nada: dime «envíalo» si quieres abrir la app."
        return Outcome.done(msg, data, warnings = warnings, cards = listOf(ResultCard.Info("Borrador ${if (channel == "generic") "" else "($channel)"}".trim(), listOfNotNull(subject?.let { "Asunto: $it" }, text))))
    }
}

// ============================================================ email_assistant

class EmailAssistantSkill : Skill {
    override val id = "email_assistant"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val warnings = mutableListOf<String>()
        return when (input.str("action")) {
            "draft" -> {
                val purpose = input.str("purpose") ?: return Outcome.invalid("Indica el propósito del correo.")
                val ai = ctx.tryModel("Redacta un correo ${input.str("tone") ?: "formal"} con asunto en la primera línea («Asunto: …»). Propósito: $purpose. No inventes datos.", input.str("notes") ?: purpose, warnings, "La redacción con IA")
                val subject = ai?.lineSequence()?.firstOrNull { it.startsWith("Asunto:") }?.removePrefix("Asunto:")?.trim() ?: input.str("subject") ?: Drafting.subject(purpose)
                val body = ai?.lineSequence()?.dropWhile { it.startsWith("Asunto:") }?.joinToString("\n")?.trim() ?: Drafting.body(purpose, input.str("notes"), input.str("recipient_name"), input.str("tone") ?: "formal", "email")
                save(ctx, input, subject, body, warnings, ai != null)
            }
            "reply" -> {
                val original = input.str("original_email") ?: return Outcome.invalid("Pega el correo original (original_email).")
                val questions = SkillText.sentences(original).filter { it.contains('?') }
                val requests = SkillText.sentences(original).filter { s -> SkillText.containsAny(s, "favor", "podria", "podrías", "necesito", "necesitamos", "solicito", "envie", "envíe", "enviar", "confirmar", "adjunt") && !s.contains('?') }
                val ai = ctx.tryModel("Redacta una respuesta ${input.str("tone") ?: "formal"} a este correo, atendiendo cada pregunta y solicitud. Usa estas indicaciones del usuario: ${input.str("notes") ?: "ninguna"}. No inventes datos ni compromisos.", original, warnings, "La respuesta con IA")
                val body = ai ?: buildString {
                    append("Estimado/a:\n\nGracias por su correo.")
                    if (questions.isNotEmpty()) { append(" Sobre sus consultas:\n"); questions.forEach { append("\n- ").append(it.trim()).append("\n  → [respuesta]") } }
                    if (requests.isNotEmpty()) { append("\n\nSobre lo solicitado:\n"); requests.forEach { append("\n- ").append(it.trim()).append("\n  → [respuesta]") } }
                    input.str("notes")?.let { append("\n\n").append(it) }
                    append("\n\nSaludos cordiales.")
                }
                val subject = input.str("subject")?.let { if (it.startsWith("Re:", true)) it else "Re: $it" } ?: "Re:"
                save(ctx, input, subject, body, warnings, ai != null, extra = mapOf("questions" to questions, "requests" to requests))
            }
            "improve" -> {
                val body = input.str("body") ?: return Outcome.invalid("Incluye el cuerpo del correo (body).")
                val (fixed, issues) = SpanishFixer.fix(body)
                val ai = ctx.tryModel("Mejora este correo: claro, cortés y profesional, conservando todos los datos. Devuelve solo el correo.", fixed, warnings, "La mejora con IA")
                save(ctx, input, input.str("subject") ?: Drafting.subject(SkillText.sentences(fixed).firstOrNull() ?: "Correo"), ai ?: fixed, warnings, ai != null, extra = mapOf("corrections" to issues.map { it.message }))
            }
            "triage" -> {
                val text = input.str("emails_text") ?: return Outcome.invalid("Pega los correos separados por una línea con ---.")
                val items = text.split(Regex("(?m)^-{3,}\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
                val triaged = items.mapIndexed { i, e ->
                    val f = TextNormalizer.fold(e)
                    val from = Regex("(?im)^(de|from):\\s*(.+)$").find(e)?.groupValues?.get(2)?.trim()
                    val subj = Regex("(?im)^(asunto|subject):\\s*(.+)$").find(e)?.groupValues?.get(2)?.trim()
                    val urgent = listOf("urgente", "hoy", "inmediat", "vence", "plazo", "ultimo aviso", "asap").count { f.contains(it) }
                    val money = listOf("factura", "pago", "cobro", "transferencia", "deposito", "cotizacion").count { f.contains(it) }
                    val meeting = listOf("reunion", "cita", "agenda", "llamada").count { f.contains(it) }
                    val action = e.contains('?') || listOf("favor", "necesito", "solicito", "confirmar", "responder").any { f.contains(it) }
                    val priority = when { urgent > 0 && (action || money > 0) -> "alta"; urgent > 0 || money > 0 || meeting > 0 && action -> "media"; else -> "baja" }
                    mapOf("n" to i + 1, "from" to from, "subject" to (subj ?: SkillText.truncate(e.lines().first(), 60)), "priority" to priority,
                        "category" to when { money > 0 -> "finanzas"; meeting > 0 -> "reuniones"; else -> "general" }, "needs_reply" to action)
                }.sortedBy { listOf("alta", "media", "baja").indexOf(it["priority"]) }
                Outcome.done("Clasifiqué ${triaged.size} correos: " + triaged.groupingBy { it["priority"] as String }.eachCount().entries.joinToString { "${it.value} prioridad ${it.key}" } + ".",
                    jsonOf("action" to "triage", "emails" to triaged), warnings = listOf("Clasificación por palabras clave sobre el texto que pegaste; JARVIS no lee tu bandeja de entrada."),
                    cards = listOf(ResultCard.Info("Correos por prioridad", triaged.map { "[${it["priority"]}] ${it["subject"]}" + (it["from"]?.let { f -> " — $f" } ?: "") })))
            }
            "open_draft" -> {
                val id = input.str("draft_id") ?: return Outcome.invalid("Falta draft_id.")
                val d = ctx.drafts().get(id)?.takeIf { it.channel == "email" } ?: return Outcome.fail("not_found", "No existe el borrador de correo $id.", "Pide uno nuevo con action=draft.")
                val to = input.strings("to").ifEmpty { listOfNotNull(d.recipient?.takeIf { '@' in it }) }
                val r = ctx.tool("compose_email", jsonOf("to" to to, "subject" to d.subject, "body" to d.body))
                if (!r.ok) return Outcome.fromTool(r, "Abrir el correo")
                ctx.drafts().save(id, d.copy(status = "opened"))
                Outcome.done(r.message, jsonOf("action" to "open_draft", "draft" to mapOf("id" to id, "subject" to d.subject), "opened" to true))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }

    private suspend fun save(ctx: SkillContext, input: JsonObject, subject: String, body: String, warnings: MutableList<String>, usedModel: Boolean, extra: Map<String, Any?> = emptyMap()): SkillOutcome {
        val col = ctx.drafts()
        val id = col.nextId()
        val to = input.strings("to")
        col.save(id, Draft("email", to.firstOrNull(), subject, body, ctx.nowIso()))
        var opened = false
        if (input.bool("open") == true) {
            val r = ctx.tool("compose_email", jsonOf("to" to to, "subject" to subject, "body" to body))
            if (r.ok) { opened = true; col.save(id, Draft("email", to.firstOrNull(), subject, body, ctx.nowIso(), status = "opened")) } else warnings += r.message
        }
        val data = jsonMap(mapOf("action" to input.str("action"), "draft" to mapOf("id" to id, "subject" to subject, "body" to body, "to" to to), "opened" to opened, "used_model" to usedModel) + extra)
        return Outcome.done(if (opened) "Abrí tu app de correo con el borrador; envíalo tú." else "Borrador de correo listo (id $id). No se envió nada.", data, warnings = warnings,
            cards = listOf(ResultCard.Info("Correo: $subject", listOf(body))))
    }

    private fun jsonMap(map: Map<String, Any?>) = jsonOf(*map.toList().toTypedArray())
}

// ============================================================ messaging_assistant

class MessagingAssistantSkill : Skill {
    override val id = "messaging_assistant"

    private val templates = mapOf(
        "recordatorio_pago" to "Hola {nombre}, le recuerdo amablemente el pago pendiente {detalle}. Si ya lo realizó, por favor ignore este mensaje. ¡Gracias!",
        "confirmacion_cita" to "Hola {nombre}, le confirmo nuestra cita {detalle}. Si necesita cambiarla, me avisa por este medio.",
        "seguimiento_cotizacion" to "Hola {nombre}, ¿pudo revisar la cotización {detalle}? Quedo atento a cualquier consulta.",
        "agradecimiento" to "Hola {nombre}, muchas gracias por su compra {detalle}. ¡Fue un gusto atenderle!",
        "cobro_amable" to "Hola {nombre}, ¿cómo está? Le escribo por el saldo pendiente {detalle}. ¿Me confirma cuándo podría cancelarlo? Gracias.",
    )

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val channel = input.str("channel") ?: "whatsapp"
        val name = input.str("contact_name") ?: ""
        return when (input.str("action")) {
            "templates" -> Outcome.done("Tengo ${templates.size} plantillas: ${templates.keys.joinToString()}.", jsonOf("action" to "templates", "templates" to templates.map { (k, v) -> mapOf("id" to k, "text" to v) }))
            "draft", "open" -> {
                val text = input.str("text") ?: input.str("template")?.let { t ->
                    templates[t]?.replace("{nombre}", name)?.replace("{detalle}", input.str("detail") ?: "")?.replace(Regex("\\s+([.,?])"), "$1")?.replace(Regex(" {2,}"), " ")?.replace("Hola ,", "Hola,")
                } ?: return Outcome.invalid("Indica el texto o una plantilla (${templates.keys.joinToString()}).")
                val id = ctx.drafts().nextId()
                ctx.drafts().save(id, Draft(channel, input.str("phone") ?: name.ifBlank { null }, null, text, ctx.nowIso()))
                if (input.str("action") == "draft") return Outcome.done("Mensaje listo (id $id). No se envió nada.", jsonOf("action" to "draft", "draft" to mapOf("id" to id, "channel" to channel, "text" to text), "opened" to false), cards = listOf(ResultCard.Info("Mensaje ($channel)", listOf(text))))
                if (channel == "whatsapp" && input.str("phone") == null) return Outcome.invalid("Para abrir WhatsApp necesito el número (phone).")
                val r = ctx.tool("compose_message", jsonOf("channel" to channel, "phone" to input.str("phone"), "text" to text))
                if (!r.ok) return Outcome.fromTool(r, "Abrir la app de mensajes")
                Outcome.done(r.message, jsonOf("action" to "open", "draft" to mapOf("id" to id, "channel" to channel, "text" to text), "opened" to true))
            }
            "schedule_reminder" -> {
                val whenText = input.str("when") ?: return Outcome.invalid("Indica cuándo (when), p. ej. «mañana a las 9».")
                val r = ctx.tool("create_task", jsonOf("title" to "Enviar mensaje a ${name.ifBlank { input.str("phone") ?: "contacto" }}", "description" to (input.str("text") ?: input.str("template") ?: ""), "due" to whenText, "remind" to true))
                if (!r.ok) return Outcome.fromTool(r, "Programar el recordatorio")
                Outcome.done("Te recordaré enviarlo (${r.message.removeSuffix(".")}). Android no permite que JARVIS envíe mensajes solo.", jsonOf("action" to "schedule_reminder", "task" to r.data, "opened" to false))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ social_media_manager

class SocialMediaManagerSkill : Skill {
    override val id = "social_media_manager"

    private val limits = mapOf("x" to 280, "instagram" to 2200, "facebook" to 5000, "linkedin" to 3000, "tiktok" to 2200, "whatsapp_status" to 700)
    private val postTypes = listOf("educativo (un consejo útil)", "producto o servicio destacado", "testimonio de cliente", "detrás de cámaras", "pregunta a la comunidad", "promoción con fecha límite", "dato curioso del sector")

    fun hashtags(text: String, max: Int = 5): List<String> = SkillText.keyTerms(text, max + 3)
        .map { TextNormalizer.fold(it).replace(Regex("[^a-z0-9ñ]"), "") }.filter { it.length >= 4 }.distinct().take(max).map { "#$it" }

    fun fit(text: String, platform: String, tags: List<String>): String {
        val limit = limits[platform] ?: 2200
        val withTags = if (tags.isEmpty() || platform == "whatsapp_status") text else "$text\n\n${tags.joinToString(" ")}"
        if (withTags.length <= limit) return withTags
        val budget = limit - (if (platform == "x" && tags.isNotEmpty()) tags.take(2).joinToString(" ").length + 2 else 0)
        val cut = SkillText.truncate(text, budget.coerceAtLeast(20))
        return if (platform == "x" && tags.isNotEmpty()) "$cut ${tags.take(2).joinToString(" ")}".take(limit) else cut
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val warnings = mutableListOf<String>()
        val platforms = input.strings("platforms").ifEmpty { listOf("instagram", "facebook") }
        return when (input.str("action")) {
            "draft_post", "adapt" -> {
                val base = input.str("text") ?: input.str("topic")?.let { topic ->
                    ctx.tryModel("Escribe una publicación para redes sociales sobre: $topic. Tono ${input.str("tone") ?: "cercano"}, sin inventar precios ni datos, con una llamada a la acción.", topic, warnings, "La redacción con IA")
                        ?: "${topic.trim().replaceFirstChar { it.uppercase() }}. ${input.str("call_to_action") ?: "¡Escríbenos para más información!"}"
                } ?: return Outcome.invalid("Indica el tema (topic) o el texto (text).")
                val tags = if (input.bool("hashtags") != false) hashtags(base) else emptyList()
                val versions = platforms.associateWith { p -> fit(base, p, tags) }
                val col = ctx.drafts()
                val ids = versions.map { (p, t) -> col.nextId().also { id -> col.save(id, Draft("social", null, null, t, ctx.nowIso(), platform = p)) } }
                val data = jsonOf("action" to input.str("action"), "versions" to versions.map { (p, t) -> mapOf("platform" to p, "text" to t, "characters" to t.length, "limit" to limits[p]) }, "hashtags" to tags, "draft_ids" to ids)
                Outcome.done("Preparé ${versions.size} versiones (${versions.keys.joinToString()}). No se publicó nada.", data, warnings = warnings + "JARVIS no publica directamente: usa «compartir» y publica desde cada app.",
                    cards = listOf(ResultCard.Info("Publicación", versions.flatMap { (p, t) -> listOf("$p (${t.length}/${limits[p]}):", t) })))
            }
            "calendar" -> {
                val days = (input.int("days") ?: 7).coerceIn(1, 31)
                val perWeek = (input.int("posts_per_week") ?: 3).coerceIn(1, 7)
                val start = input.str("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: ctx.time.zonedNow().toLocalDate().plusDays(1)
                val step = (7.0 / perWeek)
                val topic = input.str("topic") ?: "tu negocio"
                val plan = generateSequence(0.0) { it + step }.map { it.toLong() }.takeWhile { it < days }.mapIndexed { i, off ->
                    mapOf("date" to start.plusDays(off).toString(), "type" to postTypes[i % postTypes.size], "idea" to "${postTypes[i % postTypes.size].substringBefore(" (").replaceFirstChar { it.uppercase() }} sobre $topic", "platforms" to platforms)
                }.toList()
                Outcome.done("Calendario de ${plan.size} publicaciones en $days días.", jsonOf("action" to "calendar", "plan" to plan),
                    cards = listOf(ResultCard.Info("Calendario de contenido", plan.map { "${it["date"]}: ${it["idea"]}" })))
            }
            "schedule" -> {
                val whenText = input.str("date") ?: return Outcome.invalid("Indica la fecha y hora (date).")
                val text = input.str("text") ?: return Outcome.invalid("Incluye el texto de la publicación.")
                val r = ctx.tool("create_task", jsonOf("title" to "Publicar en ${platforms.joinToString()}", "description" to SkillText.truncate(text, 900), "due" to whenText, "remind" to true))
                if (!r.ok) return Outcome.fromTool(r, "Programar el recordatorio")
                Outcome.done("Te recordaré publicarlo (${r.message.removeSuffix(".")}).", jsonOf("action" to "schedule", "task" to r.data), warnings = listOf("La publicación automática no está implementada: requiere conectar cada red con su API y permisos."))
            }
            "share" -> {
                val text = input.str("text") ?: return Outcome.invalid("Incluye el texto a compartir.")
                val r = ctx.tool("share_text", jsonOf("text" to text, "title" to "Publicación"))
                if (!r.ok) return Outcome.fromTool(r, "Compartir")
                Outcome.done(r.message, jsonOf("action" to "share", "opened" to true))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ marketing_optimizer

class MarketingOptimizerSkill : Skill {
    override val id = "marketing_optimizer"

    fun score(text: String): Pair<Int, List<String>> {
        val f = TextNormalizer.fold(text)
        val notes = mutableListOf<String>()
        var score = 50
        val words = SkillText.words(text).size
        if (words in 15..120) score += 10 else notes += if (words < 15) "Texto muy corto ($words palabras): explica el beneficio." else "Texto largo ($words palabras): resume lo esencial."
        if (Regex("(compra|escribenos|escribeme|llama|reserva|agenda|pide|visitanos|descarga|registrate|aprovecha|cotiza)").containsMatchIn(f)) score += 15 else notes += "Falta una llamada a la acción (escríbenos, reserva, pide…)."
        if (Regex("\\d").containsMatchIn(text)) score += 5 else notes += "Sin cifras: un número concreto aumenta la credibilidad."
        if (Regex("\\b(ahorra|gana|mejora|facil|rapido|garantia|gratis|exclusivo|nuevo|resultado)").containsMatchIn(f)) score += 10 else notes += "No hay palabras de beneficio (ahorra, mejora, rápido…)."
        if (Regex("\\b(hoy|ultimos|hasta el|solo por|por tiempo limitado|cupos)").containsMatchIn(f)) score += 5 else notes += "Sin urgencia: considera una fecha límite real (sin inventarla)."
        if (text.contains('?')) score += 5
        SkillText.readability(text)?.let { if (it >= 60) score += 5 else notes += "Lectura difícil: frases más cortas." }
        val youWords = Regex("\\b(tu|tus|usted|ustedes|te)\\b").findAll(f).count()
        if (youWords == 0) notes += "Habla directamente al cliente (tú/usted)." else score += 5
        return score.coerceIn(0, 100) to notes
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val warnings = mutableListOf<String>()
        return when (input.str("action")) {
            "analyze_copy" -> {
                val text = input.str("text") ?: return Outcome.invalid("Incluye el texto (text).")
                val (s, notes) = score(text)
                Outcome.done("Puntuación del texto: $s/100. ${notes.firstOrNull() ?: "Buen texto."}", jsonOf("action" to "analyze_copy", "score" to s, "suggestions" to notes),
                    cards = listOf(ResultCard.Info("Análisis del anuncio ($s/100)", notes)))
            }
            "ab_variants" -> {
                val text = input.str("text") ?: return Outcome.invalid("Incluye el texto (text).")
                val ai = ctx.tryModel("Escribe 3 variantes A/B de este texto publicitario con enfoques distintos (beneficio, pregunta, prueba social). Una por línea, sin numerar. No inventes datos.", text, warnings, "Variantes con IA")
                val sentences = SkillText.sentences(text)
                val local = listOfNotNull(
                    "¿${sentences.firstOrNull()?.trimEnd('.', '!')?.replaceFirstChar { it.lowercase() }}?".takeIf { sentences.isNotEmpty() }?.let { q -> "$q ${sentences.drop(1).joinToString(" ")}".trim() },
                    sentences.reversed().joinToString(" ").takeIf { sentences.size > 1 },
                    (text.trimEnd('.') + ". ¡Escríbenos hoy!").takeIf { !TextNormalizer.fold(text).contains("escrib") },
                )
                val variants = (ai?.lines()?.map { it.trim().trimStart('-', '•', ' ') }?.filter { it.length > 10 } ?: local).take(3)
                Outcome.done("Preparé ${variants.size} variantes para probar.", jsonOf("action" to "ab_variants", "variants" to variants.map { v -> mapOf("text" to v, "score" to score(v).first) }, "used_model" to (ai != null)), warnings = warnings + "Prueba cada variante con el mismo público y presupuesto para comparar.")
            }
            "campaign_checklist" -> {
                val channel = input.str("channel") ?: "redes sociales"
                val list = listOf("Objetivo medible (ventas, mensajes, visitas) y fecha", "Público objetivo definido (zona, edad, intereses)", "Oferta clara y verdadera; sin precios inventados",
                    "Llamada a la acción única", "Enlace con UTM para medir resultados", "Presupuesto y duración", "Imagen o video propio con buena luz", "Respuesta rápida a mensajes (≤ 1 hora)",
                    "Revisión de resultados a los 3 y 7 días") + if (TextNormalizer.fold(channel).contains("correo")) listOf("Asunto de menos de 50 caracteres", "Opción para darse de baja") else emptyList()
                Outcome.done("Lista de verificación para campaña en $channel (${list.size} puntos).", jsonOf("action" to "campaign_checklist", "checklist" to list), cards = listOf(ResultCard.Info("Campaña: $channel", list.map { "☐ $it" })))
            }
            "utm" -> {
                val url = input.str("url") ?: return Outcome.invalid("Incluye la URL.")
                if (!url.startsWith("http")) return Outcome.invalid("La URL debe empezar por http:// o https://")
                fun enc(s: String) = URLEncoder.encode(s.trim().lowercase().replace(' ', '_'), "UTF-8")
                val params = listOfNotNull("utm_source" to (input.str("source") ?: return Outcome.invalid("Falta source (p. ej. instagram).")), "utm_medium" to (input.str("medium") ?: "social"), "utm_campaign" to (input.str("campaign") ?: return Outcome.invalid("Falta campaign.")), input.str("content")?.let { "utm_content" to it })
                val tagged = url + (if ('?' in url) "&" else "?") + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
                Outcome.done("Enlace con UTM listo.", jsonOf("action" to "utm", "url" to tagged))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ website_builder

class WebsiteBuilderSkill : Skill {
    override val id = "website_builder"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun build(input: JsonObject, today: String): String {
        val name = input.str("business_name")!!
        val color = input.str("color")?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) } ?: "#c46a00"
        val services = input.strings("services")
        val phone = input.str("phone")?.filter { it.isDigit() || it == '+' }
        val wa = input.str("whatsapp")?.filter { it.isDigit() }
        val email = input.str("email")
        val lat = input.double("lat"); val lon = input.double("lon")
        val ld = buildString {
            append("{\"@context\":\"https://schema.org\",\"@type\":\"LocalBusiness\",\"name\":${q(name)}")
            input.str("description")?.let { append(",\"description\":${q(it)}") }
            phone?.let { append(",\"telephone\":${q(it)}") }
            email?.let { append(",\"email\":${q(it)}") }
            input.str("address")?.let { append(",\"address\":${q(it)}") }
            if (lat != null && lon != null) append(",\"geo\":{\"@type\":\"GeoCoordinates\",\"latitude\":$lat,\"longitude\":$lon}")
            append("}")
        }
        return buildString {
            append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(esc(name)).append("</title><meta name=\"description\" content=\"").append(esc(input.str("description") ?: name)).append("\">")
            append("<script type=\"application/ld+json\">").append(ld.replace("</", "<\\/")).append("</script>")
            append("<style>:root{--c:$color}*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;color:#1d1d1d;line-height:1.5}")
            append("header{background:var(--c);color:#fff;padding:48px 16px;text-align:center}header h1{margin:0 0 8px;font-size:2rem}main{max-width:860px;margin:auto;padding:24px 16px}")
            append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}.card{border:1px solid #e3e3e3;border-radius:10px;padding:16px}")
            append(".btn{display:inline-block;background:var(--c);color:#fff;padding:12px 18px;border-radius:8px;text-decoration:none;margin:4px}footer{text-align:center;color:#777;padding:24px;font-size:.85rem}</style></head><body>")
            append("<header><h1>").append(esc(name)).append("</h1>")
            input.str("description")?.let { append("<p>").append(esc(it)).append("</p>") }
            append("</header><main>")
            if (services.isNotEmpty()) { append("<h2>Servicios</h2><div class=\"grid\">"); services.forEach { append("<div class=\"card\">").append(esc(it)).append("</div>") }; append("</div>") }
            append("<h2>Contacto</h2><p>")
            wa?.let { append("<a class=\"btn\" href=\"https://wa.me/").append(it).append("\">WhatsApp</a>") }
            phone?.let { append("<a class=\"btn\" href=\"tel:").append(it).append("\">Llamar</a>") }
            email?.let { append("<a class=\"btn\" href=\"mailto:").append(esc(it)).append("\">Correo</a>") }
            append("</p>")
            input.str("address")?.let { append("<p><strong>Dirección:</strong> ").append(esc(it)).append("</p>") }
            input.str("hours")?.let { append("<p><strong>Horario:</strong> ").append(esc(it)).append("</p>") }
            if (lat != null && lon != null) append("<p><a href=\"https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon\">Ver en el mapa (OpenStreetMap)</a></p>")
            append("</main><footer>© ").append(today.take(4)).append(" ").append(esc(name)).append("</footer></body></html>")
        }
    }

    /** Cadena JSON segura dentro de <script>: sin < ni > literales. */
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("<", "\\u003c").replace(">", "\\u003e") + "\""

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val name = input.str("business_name")!!
        if (input.str("phone") == null && input.str("whatsapp") == null && input.str("email") == null) return Outcome.invalid("Incluye al menos un medio de contacto (teléfono, WhatsApp o correo).")
        val html = build(input, ctx.time.zonedNow().toLocalDate().toString())
        val warnings = mutableListOf("La publicación en Internet no está automatizada: sube el archivo a un hosting (p. ej. GitHub Pages o Netlify).")
        val file = if (input.bool("save") != false) ctx.saveDoc(SkillText.fileName(name, "html"), "text/html", html, warnings) else null
        val data = jsonOf("business_name" to name, "html_bytes" to html.toByteArray().size, "file" to file, "preview" to html.take(600))
        return Outcome.partial("Sitio web de una página para \"$name\" generado" + (file?.str("location")?.let { " y guardado en $it" } ?: "") + ".", data, warnings,
            listOf(SkillSource("Datos aportados por el usuario", kind = "usuario")))
    }
}
