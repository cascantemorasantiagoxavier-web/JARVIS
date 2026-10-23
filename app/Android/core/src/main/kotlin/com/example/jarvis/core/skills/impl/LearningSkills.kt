package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.str
import com.example.jarvis.core.memory.TextRanker
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.text.TextNormalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** Repetición espaciada SM-2 (SuperMemo 2): nota 0–5; < 3 reinicia la tarjeta. */
object Sm2 {
    fun review(c: Flashcard, grade: Int, today: LocalDate): Flashcard {
        val g = grade.coerceIn(0, 5)
        var reps = c.repetitions; var interval = c.intervalDays; var lapses = c.lapses
        if (g < 3) { reps = 0; interval = 1; lapses++ } else {
            interval = when (reps) { 0 -> 1; 1 -> 6; else -> (interval * c.ease).roundToInt() }
            reps++
        }
        val ease = max(1.3, c.ease + (0.1 - (5 - g) * (0.08 + (5 - g) * 0.02)))
        return c.copy(ease = ease, intervalDays = interval, repetitions = reps, due = today.plusDays(interval.toLong()).toString(), lapses = lapses)
    }

    fun grade(label: String?): Int? = when (TextNormalizer.fold(label ?: return null)) { "otra_vez", "otra vez", "mal" -> 1; "dificil" -> 3; "bien" -> 4; "facil" -> 5; else -> label.toIntOrNull()?.coerceIn(0, 5) }
}

/** Operaciones de tarjetas compartidas por el tutor y el entrenador de idiomas. */
internal object Cards {
    suspend fun add(ctx: SkillContext, deck: String, pairs: List<Pair<String, String>>): Pair<Int, Int> {
        val col = ctx.flashcards()
        val existing = col.values().filter { it.deck == deck }.map { TextNormalizer.fold(it.front) }.toMutableSet()
        val today = ctx.time.zonedNow().toLocalDate().toString()
        var next = col.nextId().toLong()
        val fresh = pairs.filter { (f, b) -> f.isNotBlank() && b.isNotBlank() && existing.add(TextNormalizer.fold(f)) }
        ctx.services.store.putAll(fresh.map { (f, b) -> col.record((next++).toString(), Flashcard(deck, f.trim(), b.trim(), due = today)) })
        return fresh.size to (pairs.size - fresh.size)
    }

    suspend fun study(ctx: SkillContext, deckFilter: (String) -> Boolean, deck: String?): SkillOutcome {
        val today = ctx.time.zonedNow().toLocalDate()
        val due = ctx.flashcards().all().filter { (_, c) -> deckFilter(c.deck) && (deck == null || c.deck == deck) && !LocalDate.parse(c.due).isAfter(today) }.sortedBy { it.second.due }
        val next = due.firstOrNull() ?: return Outcome.done("No tienes tarjetas pendientes hoy" + (deck?.let { " en «$it»" } ?: "") + ". ¡Buen trabajo!", jsonOf("action" to "study", "due" to 0, "card" to null))
        return Outcome.done("Tarjeta ${next.first} (${due.size} pendientes): ${next.second.front}", jsonOf("action" to "study", "due" to due.size, "card" to mapOf("id" to next.first, "deck" to next.second.deck, "front" to next.second.front)),
            cards = listOf(ResultCard.Info("Pregunta", listOf(next.second.front, "Responde y dime cómo te fue: otra vez, difícil, bien o fácil."))))
    }

    suspend fun answer(ctx: SkillContext, input: JsonObject): SkillOutcome {
        val id = input.str("card_id") ?: return Outcome.invalid("Falta card_id.")
        val col = ctx.flashcards()
        val card = col.get(id) ?: return Outcome.fail("not_found", "No existe la tarjeta $id.", "Pide «estudiar» para ver la siguiente.")
        val auto = input.str("answer")?.let { a -> val sim = TextRanker.similarity(a, card.back); when { sim >= 0.6 -> 4; sim >= 0.3 -> 3; else -> 1 } }
        val grade = Sm2.grade(input.str("grade")) ?: auto ?: return Outcome.invalid("Indica cómo te fue (grade: otra_vez, difícil, bien, fácil) o tu respuesta (answer).")
        val updated = Sm2.review(card, grade, ctx.time.zonedNow().toLocalDate())
        col.save(id, updated)
        return Outcome.done((if (grade >= 3) "¡Bien! " else "Repasémosla pronto. ") + "Respuesta: ${card.back}. Próximo repaso: ${updated.due}.",
            jsonOf("action" to "answer", "card" to mapOf("id" to id, "front" to card.front, "back" to card.back, "grade" to grade, "next_due" to updated.due, "interval_days" to updated.intervalDays, "ease" to Tabular.round(updated.ease))))
    }

    suspend fun progress(ctx: SkillContext, deckFilter: (String) -> Boolean): SkillOutcome {
        val today = ctx.time.zonedNow().toLocalDate()
        val cards = ctx.flashcards().values().filter { deckFilter(it.deck) }
        val decks = cards.groupBy { it.deck }.map { (d, l) -> mapOf("deck" to d, "total" to l.size, "due_today" to l.count { !LocalDate.parse(it.due).isAfter(today) }, "learned" to l.count { it.intervalDays >= 21 }, "lapses" to l.sumOf { it.lapses }) }
        return Outcome.done(if (cards.isEmpty()) "Todavía no tienes tarjetas." else "${cards.size} tarjetas en ${decks.size} mazos; ${decks.sumOf { it["due_today"] as Int }} para repasar hoy.", jsonOf("action" to "progress", "decks" to decks),
            cards = listOf(ResultCard.Info("Progreso de estudio", decks.map { "${it["deck"]}: ${it["total"]} tarjetas, ${it["due_today"]} hoy, ${it["learned"]} aprendidas" })))
    }
}

// ============================================================ personal_tutor

class PersonalTutorSkill : Skill {
    override val id = "personal_tutor"
    private val notLanguage: (String) -> Boolean = { !it.startsWith("idioma:") }

    /** Tarjetas a partir de apuntes: "término: definición" o "X es Y". */
    fun cardsFromText(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        text.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.isNotBlank() }.forEach { line ->
            val m = Regex("^([^:–\\-]{2,60})\\s*[:–-]\\s+(.{3,400})$").find(line)
            if (m != null) out += "¿Qué es ${m.groupValues[1].trim().trimEnd('.')}?" to m.groupValues[2].trim()
            else SkillText.sentences(line).forEach { s ->
                Regex("^(?:El |La |Los |Las |Un |Una )?([\\p{L}0-9 ]{2,40}?) (es|son) (.{5,300})$").find(s.trim())?.let { mm ->
                    if (SkillText.words(mm.groupValues[1]).size <= 5) out += "¿Qué ${if (mm.groupValues[2] == "son") "son" else "es"} ${mm.groupValues[1].trim()}?" to s.trim()
                }
            }
        }
        return out.distinctBy { TextNormalizer.fold(it.first) }
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val warnings = mutableListOf<String>()
        return when (input.str("action")) {
            "create_cards" -> {
                val deck = input.str("deck") ?: input.str("topic") ?: "general"
                val explicit = input.arr("cards")?.map { it.jsonObject }?.mapNotNull { c -> c.str("front")?.let { f -> c.str("back")?.let { b -> f to b } } }.orEmpty()
                val fromText = input.str("text")?.let { cardsFromText(it) }.orEmpty()
                val fromAi = if (explicit.isEmpty() && fromText.isEmpty()) input.str("topic")?.let { topic ->
                    ctx.tryModel("Crea 8 tarjetas de estudio sobre \"$topic\" para nivel ${input.str("level") ?: "básico"}. Formato: una por línea, «pregunta | respuesta». Respuestas breves y correctas.", topic, warnings, "Crear tarjetas con IA")
                        ?.lines()?.mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0].trim().trimStart('-', '•', ' ') to it[1].trim() } }
                }.orEmpty() else emptyList()
                val all = explicit + fromText + fromAi
                if (all.isEmpty()) return Outcome.fail("no_cards", "No encontré contenido para crear tarjetas.", "Envía tarjetas (front/back), apuntes con «término: definición» o un tema con el servidor configurado.")
                val (added, dup) = Cards.add(ctx, deck, all)
                Outcome.done("Agregué $added tarjetas al mazo «$deck»" + (if (dup > 0) " ($dup repetidas omitidas)" else "") + ".", jsonOf("action" to "create_cards", "deck" to deck, "added" to added, "duplicates" to dup, "used_model" to fromAi.isNotEmpty()), warnings = warnings)
            }
            "study" -> Cards.study(ctx, notLanguage, input.str("deck"))
            "answer" -> Cards.answer(ctx, input)
            "progress" -> Cards.progress(ctx, notLanguage)
            "quiz" -> {
                val deck = input.str("deck")
                val pool = ctx.flashcards().all().filter { (_, c) -> notLanguage(c.deck) && (deck == null || c.deck == deck) }
                if (pool.size < 4) return Outcome.fail("not_enough_cards", "Necesito al menos 4 tarjetas para un cuestionario (hay ${pool.size}).", "Crea más tarjetas primero.")
                val n = (input.int("questions") ?: 5).coerceAtMost(pool.size)
                val questions = pool.shuffled(java.util.Random(pool.size.toLong())).take(n).map { (id, c) ->
                    val distractors = pool.filter { it.first != id }.shuffled(java.util.Random(id.hashCode().toLong())).take(3).map { it.second.back }
                    val options = (distractors + c.back).shuffled(java.util.Random(c.front.hashCode().toLong()))
                    mapOf("card_id" to id, "question" to c.front, "options" to options, "answer_index" to options.indexOf(c.back))
                }
                Outcome.done("Cuestionario de $n preguntas.", jsonOf("action" to "quiz", "questions" to questions),
                    cards = listOf(ResultCard.Info("Cuestionario", questions.flatMap { q -> listOf(q["question"] as String) + (q["options"] as List<*>).mapIndexed { i, o -> "  ${'A' + i}) $o" } })))
            }
            "explain" -> {
                val q = input.str("question") ?: input.str("topic") ?: return Outcome.invalid("Indica qué quieres que te explique.")
                val ans = ctx.tryModel("Explica de forma clara y didáctica para nivel ${input.str("level") ?: "básico"}: $q. Usa un ejemplo y termina con 2 preguntas de repaso.", q, warnings, "La explicación")
                if (ans == null) Outcome.partial("Para explicaciones necesito el modelo del servidor. Mientras tanto puedo crear tarjetas con tus apuntes.", jsonOf("action" to "explain", "explanation" to null, "used_model" to false), warnings)
                else Outcome.done(SkillText.truncate(ans, 600), jsonOf("action" to "explain", "explanation" to ans, "used_model" to true), warnings = warnings, cards = listOf(ResultCard.Info(q, listOf(ans))))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }
}

// ============================================================ language_coach

class LanguageCoachSkill : Skill {
    override val id = "language_coach"
    private val isLanguage: (String) -> Boolean = { it.startsWith("idioma:") }

    private val phrasebook = mapOf(
        "en" to listOf("Hola, ¿cómo está?" to "Hello, how are you?", "Mucho gusto" to "Nice to meet you", "¿Cuánto cuesta?" to "How much is it?", "¿Dónde está el baño?" to "Where is the restroom?",
            "Quisiera hacer una reservación" to "I would like to make a reservation", "¿Me puede ayudar, por favor?" to "Could you help me, please?", "La cuenta, por favor" to "The check, please", "Le envío la cotización hoy" to "I will send you the quote today"),
        "pt" to listOf("Hola, ¿cómo está?" to "Olá, como vai?", "Mucho gusto" to "Muito prazer", "¿Cuánto cuesta?" to "Quanto custa?", "¿Dónde está el baño?" to "Onde fica o banheiro?",
            "Quisiera hacer una reservación" to "Gostaria de fazer uma reserva", "¿Me puede ayudar, por favor?" to "Pode me ajudar, por favor?", "La cuenta, por favor" to "A conta, por favor", "Gracias" to "Obrigado / Obrigada"),
        "fr" to listOf("Hola, ¿cómo está?" to "Bonjour, comment allez-vous ?", "Mucho gusto" to "Enchanté(e)", "¿Cuánto cuesta?" to "Combien ça coûte ?", "¿Dónde está el baño?" to "Où sont les toilettes ?",
            "Quisiera hacer una reservación" to "Je voudrais faire une réservation", "¿Me puede ayudar, por favor?" to "Pouvez-vous m'aider, s'il vous plaît ?", "La cuenta, por favor" to "L'addition, s'il vous plaît", "Gracias" to "Merci"),
    )

    /** Errores frecuentes de hispanohablantes en inglés (reglas seguras). */
    fun checkEnglish(sentence: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        val s = " $sentence "
        fun add(rule: String, msg: String, fix: String) { out += mapOf("rule" to rule, "message" to msg, "suggestion" to fix) }
        if (Regex("(?<![A-Za-z])i(?![A-Za-z'])").containsMatchIn(sentence)) add("pronombre", "«I» (yo) siempre va en mayúscula.", sentence.replace(Regex("(?<![A-Za-z])i(?![A-Za-z'])"), "I"))
        Regex("(?i)\\ba ([aeiou]\\w*)").find(s)?.let { m -> if (!Regex("(?i)^(uni|use|eu|one)").containsMatchIn(m.groupValues[1])) add("artículo", "Antes de sonido vocal se usa «an»: «an ${m.groupValues[1]}».", sentence.replace(m.value.trim(), "an ${m.groupValues[1]}")) }
        Regex("(?i)\\bI have (\\d+) years\\b").find(sentence)?.let { add("edad", "La edad se dice con «to be»: «I am ${it.groupValues[1]} years old».", sentence.replace(it.value, "I am ${it.groupValues[1]} years old")) }
        Regex("(?i)\\bpeople is\\b").find(sentence)?.let { add("concordancia", "«people» es plural: «people are».", sentence.replace(it.value, "people are")) }
        Regex("(?i)\\bdepends? of\\b").find(sentence)?.let { add("preposición", "Se dice «depend on».", sentence.replace(it.value, it.value.replace(Regex("(?i)of"), "on"))) }
        Regex("(?i)\\bexplain me\\b").find(sentence)?.let { add("estructura", "Se dice «explain to me».", sentence.replace(it.value, "explain to me")) }
        Regex("(?i)\\bmake a question\\b").find(sentence)?.let { add("colocación", "Se dice «ask a question».", sentence.replace(it.value, "ask a question")) }
        Regex("(?i)\\bthe (people|life|love|nature) (is|are)\\b").find(sentence)?.let { add("artículo", "En general no se usa «the» con sustantivos abstractos o generales.", sentence.replace(it.value, it.value.substring(4).replaceFirstChar { c -> c.uppercase() })) }
        if (sentence.isNotBlank() && sentence.first().isLowerCase()) add("mayúscula", "La oración empieza con mayúscula.", sentence.replaceFirstChar { it.uppercase() })
        if (sentence.isNotBlank() && sentence.last() !in ".!?") add("puntuación", "Falta el punto final.", "$sentence.")
        return out
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val lang = input.str("language") ?: "en"
        val deck = "idioma:$lang"
        val warnings = mutableListOf<String>()
        return when (input.str("action")) {
            "add_words" -> {
                val words = input.arr("words")?.map { it.jsonObject }?.mapNotNull { w -> w.str("word")?.let { word -> w.str("translation")?.let { t -> word to (t + (w.str("example")?.let { " — «$it»" } ?: "")) } } }.orEmpty()
                if (words.isEmpty()) return Outcome.invalid("Incluye palabras con su traducción (words: [{word, translation}]).")
                val (added, dup) = Cards.add(ctx, deck, words)
                Outcome.done("Agregué $added palabras al vocabulario de ${name(lang)}" + (if (dup > 0) " ($dup ya estaban)" else "") + ".", jsonOf("action" to "add_words", "deck" to deck, "added" to added, "duplicates" to dup))
            }
            "study" -> Cards.study(ctx, isLanguage, deck)
            "answer" -> Cards.answer(ctx, input)
            "progress" -> Cards.progress(ctx, isLanguage)
            "phrasebook" -> {
                val list = phrasebook[lang] ?: return Outcome.fail("unsupported_language", "El libro de frases local tiene inglés, portugués y francés.", "Puedes agregar tus propias frases con add_words.")
                Outcome.done("${list.size} frases útiles en ${name(lang)}.", jsonOf("action" to "phrasebook", "phrases" to list.map { mapOf("es" to it.first, "translation" to it.second) }),
                    cards = listOf(ResultCard.Info("Frases en ${name(lang)}", list.map { "${it.first} → ${it.second}" })))
            }
            "check_sentence" -> {
                val sentence = input.str("sentence") ?: return Outcome.invalid("Incluye la oración a revisar.")
                val local = if (lang == "en") checkEnglish(sentence) else emptyList()
                val ai = ctx.tryModel("Corrige esta oración en ${name(lang)}. Devuelve la versión correcta y explica en español cada corrección en una línea.", sentence, warnings, "La corrección completa")
                val data = jsonOf("action" to "check_sentence", "language" to lang, "issues" to local, "ai_correction" to ai, "used_model" to (ai != null))
                val msg = when { ai != null -> SkillText.truncate(ai, 500); local.isEmpty() -> "No encontré errores con las reglas locales" + (if (lang != "en") " (solo hay reglas para inglés)" else "") + "."; else -> "${local.size} correcciones: " + local.joinToString("; ") { it.getValue("message") } }
                if (ai == null && lang != "en") Outcome.partial(msg, data, warnings) else Outcome.done(msg, data, warnings = warnings, cards = listOf(ResultCard.Info("Revisión", local.map { "${it["message"]} → ${it["suggestion"]}" } + listOfNotNull(ai))))
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }

    private fun name(lang: String) = mapOf("en" to "inglés", "pt" to "portugués", "fr" to "francés", "it" to "italiano", "de" to "alemán")[lang] ?: lang
}
