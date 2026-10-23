package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.business.ProspectStatus
import com.example.jarvis.core.json.bool
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
import java.time.Duration

/** Biblioteca local de objeciones frecuentes con técnicas reconocidas (sin IA). */
internal object ObjectionLibrary {
    data class Entry(val keys: List<String>, val label: String, val responses: List<Pair<String, String>>)

    val entries = listOf(
        Entry(listOf("caro", "precio", "costoso", "presupuesto", "barato"), "precio", listOf(
            "Aislar la objeción" to "Entiendo. Aparte del precio, ¿hay algo más que le impida avanzar?",
            "Valor antes que precio" to "Comparémoslo con lo que le cuesta hoy no tenerlo: ¿cuánto tiempo o dinero pierde cada mes con la situación actual?",
            "Opciones" to "Podemos empezar con una versión más pequeña o en pagos, y ampliar cuando vea resultados. ¿Le serviría?",
        )),
        Entry(listOf("pensar", "pensarlo", "luego", "despues", "consultar"), "lo voy a pensar", listOf(
            "Sentir-sentido-descubierto" to "Lo entiendo; otros clientes sintieron lo mismo y descubrieron que les ayudó aclarar sus dudas concretas. ¿Qué parte quiere pensar con más calma?",
            "Siguiente paso concreto" to "Perfecto. ¿Le parece si lo conversamos el jueves a las 10 y así tiene tiempo de revisarlo con su equipo?",
        )),
        Entry(listOf("proveedor", "ya tengo", "ya trabajo", "competencia"), "ya tengo proveedor", listOf(
            "Respetar y comparar" to "Qué bueno que ya tenga a alguien de confianza. ¿Qué es lo que más valora de su proveedor actual y qué mejoraría?",
            "Segunda opción" to "Muchas empresas tienen un proveedor de respaldo para no depender de uno solo. ¿Le interesaría tenernos como alternativa?",
        )),
        Entry(listOf("tiempo", "ocupado", "no puedo ahora"), "no tengo tiempo", listOf(
            "Brevedad" to "Lo entiendo, por eso seré breve: en dos minutos le explico la idea principal y usted decide si vale la pena una reunión.",
            "Agendar" to "¿Qué momento de la semana le resulta más cómodo para una llamada de 10 minutos?",
        )),
        Entry(listOf("informacion", "correo", "mande", "envie"), "envíeme información", listOf(
            "Personalizar" to "Con gusto. Para enviarle lo que de verdad le sirva, ¿qué es lo más importante para usted en este tema?",
            "Compromiso" to "Se la envío hoy. ¿Le parece si el martes revisamos juntos sus dudas?",
        )),
        Entry(listOf("confianza", "no conozco", "seguro", "garantia"), "desconfianza", listOf(
            "Prueba social" to "Es razonable. Puedo darle referencias de clientes de la zona con los que trabajamos.",
            "Reducir el riesgo" to "Podemos empezar con un pedido pequeño de prueba para que compruebe la calidad.",
        )),
    )

    fun match(objection: String): Entry? {
        val f = TextNormalizer.fold(objection)
        return entries.maxByOrNull { e -> e.keys.count { f.contains(it) } }?.takeIf { e -> e.keys.any { f.contains(it) } }
    }
}

class SalesCoachSkill : Skill {
    override val id = "sales_coach"

    override fun parseText(text: String): JsonObject? {
        val f = TextNormalizer.canonical(text)
        return when {
            SkillText.containsAny(f, "embudo", "pipeline", "como van mis ventas") -> jsonOf("action" to "pipeline_review")
            SkillText.containsAny(f, "necesitan seguimiento", "a quien debo dar seguimiento") -> jsonOf("action" to "follow_up_plan")
            Regex("(responder|respondo|contesto|contestar).*(dice|dicen|objecion)").containsMatchIn(f) -> {
                val obj = Regex("(?:dice|dicen) que (.+)$").find(f)?.groupValues?.get(1) ?: return null
                jsonOf("action" to "objection_practice", "objection" to obj)
            }
            else -> null
        }
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val action = input.str("action")!!
        val warnings = mutableListOf<String>()
        return when (action) {
            "pipeline_review", "follow_up_plan" -> pipeline(ctx, action, input.bool("create_tasks") == true, warnings)
            "objection_practice" -> {
                val objection = input.str("objection") ?: return Outcome.invalid("Indica la objeción del cliente.")
                val entry = ObjectionLibrary.match(objection)
                val local = entry?.responses?.map { mapOf("technique" to it.first, "text" to it.second) }.orEmpty()
                val ai = ctx.tryModel(
                    "Eres entrenador de ventas. El cliente dijo: \"$objection\"" + (input.str("product")?.let { " sobre $it" } ?: "") +
                        ". Propón 2 respuestas breves, honestas y respetuosas, cada una con el nombre de la técnica. Sin promesas falsas.",
                    objection, warnings, "Respuestas personalizadas con IA",
                )
                if (local.isEmpty() && ai == null) return Outcome.partial("No tengo esa objeción en la biblioteca local y el modelo no está disponible.", jsonOf("action" to action, "recommendations" to listOf("Escucha, valida la preocupación, pregunta qué la origina y propone un siguiente paso concreto."), "responses" to emptyList<Any>(), "used_model" to false), warnings)
                val data = jsonOf("action" to action, "objection_type" to entry?.label, "responses" to local, "ai_suggestions" to ai,
                    "recommendations" to listOf("Escucha completa antes de responder.", "Valida la preocupación sin discutir.", "Termina con una pregunta o un siguiente paso."), "used_model" to (ai != null))
                Outcome.done("Objeción tipo «${entry?.label ?: "otra"}»: ${local.size} respuestas de la biblioteca" + (if (ai != null) " y sugerencias de la IA." else "."), data, warnings = warnings,
                    cards = listOf(ResultCard.Info("Responder: \"$objection\"", local.map { "${it["technique"]}: ${it["text"]}" } + listOfNotNull(ai))))
            }
            "pitch_feedback" -> {
                val pitch = input.str("pitch_text") ?: return Outcome.invalid("Incluye el texto de tu discurso (pitch_text).")
                val f = TextNormalizer.fold(pitch)
                val words = SkillText.words(pitch).size
                val fb = mutableListOf<String>()
                fb += when { words < 40 -> "Es muy breve ($words palabras): añade el problema que resuelves y un resultado concreto."; words > 220 -> "Es largo ($words palabras): para una presentación de 1 minuto apunta a 120–160."; else -> "Longitud adecuada ($words palabras)." }
                if (!Regex("\\d").containsMatchIn(pitch)) fb += "No hay cifras: un dato concreto (ahorro, tiempo, clientes) da credibilidad."
                if (!pitch.contains('?')) fb += "No haces ninguna pregunta: incluye una para involucrar al cliente."
                if (!listOf("llam", "agend", "reun", "prueb", "visit", "escrib", "contact", "pedido").any { f.contains(it) }) fb += "Falta una llamada a la acción clara (agendar, probar, pedir)."
                val youCount = Regex("\\b(usted|ustedes|tu|su negocio|su empresa)\\b").findAll(f).count()
                val weCount = Regex("\\b(nosotros|nuestra|nuestro|yo|mi empresa)\\b").findAll(f).count()
                if (weCount > youCount * 2 && weCount > 2) fb += "Hablas más de ti ($weCount) que del cliente ($youCount): enfócate en sus beneficios."
                SkillText.readability(pitch)?.let { if (it < 50) fb += "La redacción es difícil (${SkillText.readabilityLabel(it)}): usa frases más cortas." }
                val ai = ctx.tryModel("Da retroalimentación concreta a este discurso de venta: 3 fortalezas, 3 mejoras y una versión mejorada de 120 palabras. No inventes datos del producto.", pitch, warnings, "Retroalimentación con IA")
                Outcome.done("Revisé tu discurso: ${fb.size} observaciones" + (if (ai != null) " y una versión sugerida." else "."), jsonOf("action" to action, "feedback" to fb, "ai_feedback" to ai, "recommendations" to fb, "used_model" to (ai != null)), warnings = warnings,
                    cards = listOf(ResultCard.Info("Tu discurso de venta", fb + listOfNotNull(ai))))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }

    private suspend fun pipeline(ctx: SkillContext, action: String, createTasks: Boolean, warnings: MutableList<String>): SkillOutcome {
        val prospects = ctx.services.prospects.list()
        if (prospects.isEmpty()) {
            return Outcome.done("No tienes prospectos guardados todavía. Puedo buscar negocios y guardarlos con business_prospecting.",
                jsonOf("action" to action, "pipeline" to mapOf("total" to 0), "recommendations" to listOf("Busca negocios de tu categoría objetivo y guarda los más prometedores."), "used_model" to false))
        }
        val now = ctx.time.now()
        val active = prospects.filter { it.status != ProspectStatus.CLIENTE && it.status != ProspectStatus.DESCARTADO }
        val interactions = ctx.interactions().values()
        fun lastContact(id: Long) = interactions.filter { it.prospectId == id }.maxOfOrNull { it.date }
        val stale = active.filter { Duration.between(it.updatedAt, now).toDays() >= 14 && (lastContact(it.id)?.let { d -> d < now.minus(Duration.ofDays(14)).toString() } ?: true) }
        val noNext = active.filter { it.nextAction.isNullOrBlank() }
        val byStatus = ProspectStatus.entries.associate { s -> s.label to prospects.count { it.status == s } }
        val clients = prospects.count { it.status == ProspectStatus.CLIENTE }
        val conversion = if (prospects.isNotEmpty()) Tabular.round(clients * 100.0 / prospects.size) else 0.0
        val recs = mutableListOf<String>()
        if (noNext.isNotEmpty()) recs += "${noNext.size} prospectos activos no tienen próxima acción: define una para cada uno (${noNext.take(3).joinToString { it.name }})."
        if (stale.isNotEmpty()) recs += "${stale.size} prospectos llevan 14 días o más sin movimiento: retómalos o descártalos (${stale.take(3).joinToString { it.name }})."
        val negotiating = prospects.count { it.status == ProspectStatus.NEGOCIANDO }
        if (negotiating > 0) recs += "$negotiating en negociación: priorízalos esta semana."
        if ((byStatus[ProspectStatus.NUEVO.label] ?: 0) > active.size / 2 && active.size >= 4) recs += "La mayoría siguen en «Nuevo»: dedica un bloque a primeros contactos."
        if (recs.isEmpty()) recs += "El embudo está al día."
        var created = 0
        val toFollow = (noNext + stale).distinctBy { it.id }.take(8)
        if (action == "follow_up_plan" && createTasks) {
            for (p in toFollow) {
                val r = ctx.tool("create_task", jsonOf("title" to "Seguimiento: ${p.name}", "description" to ("Estado: ${p.status.label}. " + (p.phone?.let { "Tel. $it" } ?: "")), "due" to "mañana", "priority" to "MEDIUM"))
                if (r.ok) created++ else warnings += "No se pudo crear la tarea de ${p.name}: ${r.message}"
            }
        }
        val data = jsonOf(
            "action" to action,
            "pipeline" to mapOf("total" to prospects.size, "active" to active.size, "by_status" to byStatus, "conversion_percent" to conversion, "stale" to stale.map { it.name }, "without_next_action" to noNext.map { it.name }),
            "follow_ups" to toFollow.map { mapOf("prospect_id" to it.id, "name" to it.name, "status" to it.status.label, "phone" to it.phone) },
            "recommendations" to recs, "tasks_created" to created, "used_model" to false,
        )
        val msg = "Embudo: ${prospects.size} prospectos, $clients clientes ($conversion %). " + recs.first() + if (created > 0) " Creé $created tareas de seguimiento." else ""
        return Outcome.done(msg, data, listOf(SkillSource("Prospectos guardados en el teléfono", kind = "local")), warnings,
            listOf(ResultCard.Info("Embudo de ventas", byStatus.map { "${it.key}: ${it.value}" } + recs)))
    }
}
