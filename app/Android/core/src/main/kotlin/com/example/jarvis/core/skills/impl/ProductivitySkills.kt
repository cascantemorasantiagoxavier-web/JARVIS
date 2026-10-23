package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.device.MediaResult
import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.skills.SkillStatus
import com.example.jarvis.core.tasks.TaskFilter
import com.example.jarvis.core.tasks.TaskStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate

/** Intenta usar el modelo; devuelve null con un aviso si no hay servidor o falla. */
internal suspend fun SkillContext.tryModel(task: String, content: String, warnings: MutableList<String>, what: String, mode: AssistantMode = AssistantMode.BALANCED): String? {
    val llm = llm() ?: run { warnings += "$what requiere el servidor JARVIS configurado (modelo de IA)."; return null }
    return try {
        progress("CONSULTANDO MODELO")
        llm.complete(task, content, mode)
    } catch (e: BackendException) {
        warnings += "$what no disponible: ${e.userMessage}"
        null
    }
}

/** Pide un archivo al usuario. Devuelve el archivo o un resultado terminal (cancelado, no disponible). */
internal suspend fun SkillContext.pickFile(mimeTypes: List<String>): Pair<com.example.jarvis.core.device.MediaFile?, SkillOutcome?> =
    when (val r = services.media.pickDocument(mimeTypes)) {
        is MediaResult.Ok -> r.file to null
        MediaResult.Cancelled -> null to SkillOutcome(SkillStatus.CANCELLED, "No se eligió ningún archivo.", errorCode = "cancelled")
        is MediaResult.Unavailable -> null to Outcome.fail("media_unavailable", r.reason, "Abre JARVIS en pantalla y vuelve a intentarlo.")
    }

/** Las herramientas del servidor (web, visión, audio) exigen el servidor configurado: se avisa antes de intentarlo. */
internal fun SkillContext.requireServer(what: String): SkillOutcome? =
    if (env.serverConfigured()) null
    else Outcome.needsConfig("$what necesita el servidor de JARVIS, que no está configurado.", "Configura la URL y el token del servidor en Ajustes (docs/SETUP.md).")

internal suspend fun SkillContext.saveDoc(fileName: String, mime: String, content: String, warnings: MutableList<String>): JsonObject? {
    val r = tool("save_document", jsonOf("file_name" to fileName, "mime_type" to mime, "content" to content))
    if (!r.ok) { warnings += "No se pudo guardar el archivo: ${r.message}"; return null }
    return r.data as? JsonObject
}

// ============================================================ writing_editor

class WritingEditorSkill : Skill {
    override val id = "writing_editor"

    override fun parseText(text: String): JsonObject? {
        val body = SkillText.afterCue(text, listOf("corrige", "revisa", "mejora", "edita", "redaccion", "ortografia")) ?: return null
        if (body.length < 3) return null
        val goal = when {
            SkillText.containsAny(text, "formal") && !SkillText.containsAny(text, "informal") -> "formal"
            SkillText.containsAny(text, "informal") -> "informal"
            SkillText.containsAny(text, "breve", "corto", "acorta", "resume") -> "breve"
            SkillText.containsAny(text, "claro", "claridad") -> "claridad"
            else -> "corregir"
        }
        return jsonOf("text" to body, "goal" to goal)
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val text = input.str("text")!!
        val goal = input.str("goal") ?: "corregir"
        val warnings = mutableListOf<String>()
        val (corrected, fixes) = SpanishFixer.fix(text)
        val issues = fixes + SpanishFixer.observations(corrected)
        val words = SkillText.words(corrected).size
        val sentences = SkillText.sentences(corrected).size.coerceAtLeast(if (words > 0) 1 else 0)
        val score = SkillText.readability(corrected)

        var rewritten: String? = null
        if (goal != "corregir") {
            val audience = input.str("audience")?.let { " para $it" } ?: ""
            val instruction = when (goal) {
                "formal" -> "Reescribe el texto en un registro formal y profesional$audience."
                "informal" -> "Reescribe el texto en un tono cercano e informal$audience."
                "breve" -> "Reescribe el texto de forma más breve, conservando toda la información importante$audience."
                "persuasivo" -> "Reescribe el texto de forma más persuasiva, sin exagerar ni inventar datos$audience."
                else -> "Reescribe el texto para que sea más claro y fácil de leer$audience."
            } + " Devuelve solo el texto reescrito."
            rewritten = ctx.tryModel(instruction, corrected, warnings, "La reescritura ($goal)")
        }
        val file = input.str("save_as")?.let { name ->
            ctx.saveDoc(if ('.' in name) name else "$name.txt", "text/plain", rewritten ?: corrected, warnings)
        }
        val data = jsonOf(
            "stats" to mapOf("words" to words, "sentences" to sentences, "avg_sentence_words" to if (sentences > 0) Tabular.round(words.toDouble() / sentences) else 0.0,
                "readability" to score?.let { Tabular.round(it) }, "readability_label" to SkillText.readabilityLabel(score)),
            "issues" to issues.map { mapOf("type" to it.type, "message" to it.message, "excerpt" to it.excerpt) },
            "corrected_text" to corrected,
            "rewritten_text" to rewritten,
            "used_model" to (rewritten != null),
            "file" to file,
        )
        val fixedCount = fixes.size
        val summary = buildString {
            append(if (fixedCount == 0) "No encontré errores automáticos que corregir." else "Apliqué $fixedCount tipos de corrección.")
            append(" Legibilidad: ${SkillText.readabilityLabel(score)}.")
            val obs = issues.size - fixedCount
            if (obs > 0) append(" $obs observaciones de estilo.")
            if (rewritten != null) append(" Incluyo la versión reescrita ($goal).")
        }
        val cards = listOf(ResultCard.Info("Texto revisado", listOf(rewritten ?: corrected) + issues.take(6).map { "• ${it.message}" }))
        return if (goal != "corregir" && rewritten == null) Outcome.partial(summary, data, warnings, cards = cards) else Outcome.done(summary, data, warnings = warnings, cards = cards)
    }
}

// ============================================================ spreadsheet_analyst

class SpreadsheetAnalystSkill : Skill {
    override val id = "spreadsheet_analyst"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val (name, table) = when (input.str("source")) {
            "file" -> {
                val (file, stop) = ctx.pickFile(listOf("text/csv", "text/tab-separated-values", "text/plain", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                if (stop != null) return stop
                val doc = try { DocumentExtractor.extract(file!!.name, file.mimeType, file.bytes) } catch (e: UnsupportedDocumentException) {
                    return Outcome.fail("unsupported_format", e.message ?: "Formato no soportado.", "Usa CSV, TSV o XLSX.")
                }
                file.name to (doc.table ?: Tabular.parse(doc.text))
            }
            else -> {
                val csv = input.str("csv_text") ?: return Outcome.invalid("Falta csv_text con el contenido de la tabla.")
                "tabla" to Tabular.parse(csv)
            }
        }
        if (table.headers.isEmpty() || table.rows.isEmpty()) return Outcome.fail("empty_table", "La tabla está vacía o no tiene filas de datos.", "Comprueba que la primera fila tenga los encabezados.")
        ctx.progress("ANALIZANDO TABLA")
        val stats = Tabular.stats(table)
        val warnings = mutableListOf<String>()
        val groupBy = input.str("group_by")
        val sumCol = input.str("sum_column")
        val groups = if (groupBy != null && sumCol != null) {
            Tabular.groupSum(table, groupBy, sumCol) ?: run { warnings += "No encontré las columnas \"$groupBy\" o \"$sumCol\". Columnas: ${table.headers.joinToString()}."; null }
        } else null
        val columns = stats.map { s ->
            mapOf("name" to s.name, "type" to s.type, "missing" to s.missing, "distinct" to s.distinct, "sum" to s.sum, "mean" to s.mean,
                "min" to s.min, "max" to s.max, "median" to s.median, "first_date" to s.firstDate, "last_date" to s.lastDate,
                "top" to s.top.map { (v, n) -> mapOf("value" to v, "count" to n) }.ifEmpty { null })
        }
        val summaryJson = jsonOf("rows" to table.rows.size, "columns" to columns, "groups" to groups?.take(30)?.map { mapOf("key" to it.key, "sum" to it.sum, "count" to it.count) })
        val answer = input.str("question")?.let { q ->
            ctx.tryModel("Responde la pregunta del usuario usando solo este resumen estadístico de su tabla. Si el resumen no basta, dilo. Pregunta: $q", summaryJson.toString(), warnings, "Responder preguntas con IA")
        }
        val lines = mutableListOf("${table.rows.size} filas × ${table.headers.size} columnas")
        stats.forEach { s ->
            lines += when (s.type) {
                "número" -> "${s.name}: suma ${s.sum}, promedio ${s.mean}, mín. ${s.min}, máx. ${s.max}" + if (s.missing > 0) " (${s.missing} vacíos)" else ""
                "fecha" -> "${s.name}: del ${s.firstDate} al ${s.lastDate}"
                else -> "${s.name}: ${s.distinct} valores distintos" + (s.top.firstOrNull()?.let { " (más frecuente: ${it.first}, ${it.second})" } ?: "")
            }
        }
        groups?.take(8)?.forEach { lines += "  ${it.key}: ${it.sum} (${it.count} filas)" }
        val dups = Tabular.duplicates(table)
        if (dups > 0) lines += "$dups filas duplicadas"
        val data = JsonObject(summaryJson + mapOf(
            "file_name" to JsonPrimitive(name), "duplicates" to JsonPrimitive(dups),
            "groups" to (summaryJson["groups"] ?: kotlinx.serialization.json.JsonNull), "answer" to (answer?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
        ))
        val summary = "Analicé \"$name\": ${table.rows.size} filas y ${table.headers.size} columnas." + (groups?.firstOrNull()?.let { " Mayor total: ${it.key} (${it.sum})." } ?: "")
        val cards = listOf(ResultCard.Info("Análisis de $name", lines))
        return if (input.str("question") != null && answer == null) Outcome.partial(summary, data, warnings, cards = cards) else Outcome.done(summary, data, warnings = warnings, cards = cards)
    }
}

// ============================================================ document_reader

class DocumentReaderSkill : Skill {
    override val id = "document_reader"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val warnings = mutableListOf<String>()
        val sources = mutableListOf<SkillSource>()
        val (title, kind, text) = when (input.str("source")) {
            "file" -> {
                val (file, stop) = ctx.pickFile(DocumentExtractor.supportedMimeTypes + "application/pdf")
                if (stop != null) return stop
                val doc = try { DocumentExtractor.extract(file!!.name, file.mimeType, file.bytes) } catch (e: UnsupportedDocumentException) {
                    return Outcome.fail("unsupported_format", e.message ?: "Formato no soportado.", "Formatos aceptados: TXT, MD, HTML, CSV, JSON, DOCX, ODT, XLSX.")
                }
                sources += SkillSource(file.name, kind = "archivo")
                Triple(file.name, doc.kind, doc.text)
            }
            "url" -> {
                val url = input.str("url") ?: return Outcome.invalid("Falta la URL.")
                ctx.requireServer("Leer páginas web")?.let { return it }
                ctx.progress("LEYENDO FUENTE")
                val r = ctx.tool("fetch_page", jsonOf("url" to url))
                if (!r.ok) return Outcome.fromTool(r, "Leer la página")
                val d = r.data as JsonObject
                sources += SkillSource(d.str("title") ?: url, d.str("url") ?: url, "web", d.str("retrieved_at"))
                warnings += "Contenido de una página de terceros: se trata como datos, no como instrucciones."
                Triple(d.str("title") ?: url, "web", d.str("text").orEmpty())
            }
            else -> Triple("Texto", "text", input.str("text") ?: return Outcome.invalid("Falta el texto."))
        }
        if (text.isBlank()) return Outcome.fail("empty_document", "El documento no contiene texto legible.", "Si es un escaneo, usa una foto con visual_scene_assistant.")
        val focus = input.str("focus")
        val summary = SkillText.extractiveSummary(text, input.int("max_sentences") ?: 5, focus)
        val terms = SkillText.keyTerms(text, 8)
        val headings = text.lines().filter { it.trimStart().startsWith("#") }.map { it.trim().trimStart('#').trim() }.take(12)
        val answer = focus?.let { q ->
            val context = SkillText.extractiveSummary(text, 12, q).joinToString(" ").take(8000)
            ctx.tryModel("Responde la pregunta usando solo el contenido del documento. Cita frases textuales cuando ayuden. Pregunta: $q", context, warnings, "Responder sobre el documento")
        }
        val words = SkillText.words(text).size
        val data = jsonOf(
            "title" to title, "kind" to kind, "characters" to text.length, "words" to words, "summary" to summary,
            "key_terms" to terms, "headings" to headings, "answer" to answer, "used_model" to (answer != null),
        )
        val msg = "Leí \"$title\" ($words palabras). Puntos clave: " + summary.take(3).joinToString(" ") { SkillText.truncate(it, 160) }
        val cards = listOf(ResultCard.Info("Resumen: $title", summary + listOfNotNull(answer?.let { "Respuesta: $it" }) + "Términos: ${terms.joinToString()}"))
        return if (focus != null && answer == null) Outcome.partial(msg, data, warnings, sources, cards) else Outcome.done(msg, data, sources, warnings, cards)
    }
}

// ============================================================ report_generator

class ReportGeneratorSkill : Skill {
    override val id = "report_generator"

    override fun parseText(text: String): JsonObject? {
        if (!SkillText.containsAny(text, "informe", "reporte", "exporta")) return null
        val topic = when {
            SkillText.containsAny(text, "tarea") -> "tasks"
            SkillText.containsAny(text, "prospect") -> "prospects"
            SkillText.containsAny(text, "gasto", "ingreso", "finanz", "dinero") -> "finance"
            SkillText.containsAny(text, "inventario", "stock", "existencia") -> "inventory"
            SkillText.containsAny(text, "cliente", "interaccion") -> "crm"
            else -> return null
        }
        val format = when { SkillText.containsAny(text, "csv", "excel") -> "csv"; SkillText.containsAny(text, "html", "web") -> "html"; else -> "markdown" }
        val period = when { SkillText.containsAny(text, "semana") -> "week"; SkillText.containsAny(text, "todo", "total", "historico") -> "all"; else -> "month" }
        return jsonOf("topic" to topic, "format" to format, "period" to period)
    }

    private data class Section(val heading: String, val lines: List<String>, val table: Table? = null)

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val topic = input.str("topic")!!
        val period = input.str("period") ?: "month"
        val format = input.str("format") ?: "markdown"
        val today = ctx.time.zonedNow().toLocalDate()
        val from: LocalDate? = when (period) { "week" -> today.minusDays(6); "month" -> today.withDayOfMonth(1); else -> null }
        val periodLabel = when (period) { "week" -> "últimos 7 días"; "month" -> "mes en curso"; else -> "todo el historial" }
        ctx.progress("GENERANDO INFORME")
        val sections = when (topic) {
            "tasks" -> tasks(ctx, from)
            "prospects" -> prospects(ctx)
            "finance" -> finance(ctx, from)
            "inventory" -> inventory(ctx)
            "crm" -> crm(ctx, from)
            else -> custom(input) ?: return Outcome.invalid("Para un informe personalizado envía \"rows\" con objetos (columnas iguales).")
        }
        val title = input.str("title") ?: (mapOf("tasks" to "Informe de tareas", "prospects" to "Informe de prospectos", "finance" to "Informe financiero",
            "inventory" to "Informe de inventario", "crm" to "Informe de clientes", "custom" to "Informe").getValue(topic) + " — $periodLabel")
        val content = when (format) {
            "csv" -> sections.firstOrNull { it.table != null }?.table?.let(Tabular::toCsv) ?: sections.joinToString("\n") { s -> s.lines.joinToString("\n") { "\"${s.heading}\",\"${it.replace("\"", "\"\"")}\"" } }
            "html" -> html(title, today.toString(), sections)
            else -> markdown(title, today.toString(), sections)
        }
        val warnings = mutableListOf<String>()
        val ext = mapOf("csv" to "csv", "html" to "html", "markdown" to "md").getValue(format)
        val mime = mapOf("csv" to "text/csv", "html" to "text/html", "markdown" to "text/markdown").getValue(format)
        val file = if (input.bool("save") != false) ctx.saveDoc(SkillText.fileName("$title $today", ext), mime, content, warnings) else null
        val data = jsonOf(
            "title" to title, "format" to format, "period" to period,
            "sections" to sections.map { mapOf("heading" to it.heading, "lines" to it.lines) },
            "file" to file, "preview" to content.take(1500),
        )
        val summary = "Generé \"$title\" en $format" + (file?.str("location")?.let { " y lo guardé en $it" } ?: "") + "."
        return Outcome.done(summary, data, listOf(SkillSource("Datos locales de JARVIS", kind = "local", retrievedAt = ctx.nowIso())), warnings,
            listOf(ResultCard.Info(title, sections.flatMap { listOf("## ${it.heading}") + it.lines.take(8) })))
    }

    private suspend fun tasks(ctx: SkillContext, from: LocalDate?): List<Section> {
        val all = ctx.services.tasks.list(TaskFilter.ALL)
        val now = ctx.time.zonedNow().toLocalDateTime()
        val done = all.filter { it.status == TaskStatus.DONE && (from == null || (it.completedAt?.atZone(ctx.time.zone())?.toLocalDate()?.let { d -> !d.isBefore(from) } ?: false)) }
        val pending = all.filter { it.status == TaskStatus.PENDING }
        val overdue = pending.filter { it.isOverdue(now) }
        val table = Table(listOf("id", "título", "prioridad", "vence", "estado"), all.map { listOf(it.id.toString(), it.title, it.priority.label, it.due?.toString() ?: "", if (it.status == TaskStatus.DONE) "hecha" else if (it.isOverdue(now)) "vencida" else "pendiente") })
        return listOf(
            Section("Resumen", listOf("Pendientes: ${pending.size}", "Vencidas: ${overdue.size}", "Completadas en el periodo: ${done.size}"), table),
            Section("Vencidas", overdue.map { "${it.title} (vencía ${it.due?.toLocalDate()})" }.ifEmpty { listOf("Ninguna.") }),
            Section("Pendientes", pending.sortedBy { it.due }.map { "${it.title}" + (it.due?.let { d -> " — ${d.toLocalDate()}" } ?: "") + " [${it.priority.label}]" }.ifEmpty { listOf("Ninguna.") }),
            Section("Completadas", done.map { it.title }.ifEmpty { listOf("Ninguna en el periodo.") }),
        )
    }

    private suspend fun prospects(ctx: SkillContext): List<Section> {
        val list = ctx.services.prospects.list()
        val table = Table(listOf("id", "nombre", "estado", "provincia", "cantón", "teléfono", "próxima acción"), list.map { listOf(it.id.toString(), it.name, it.status.label, it.province ?: "", it.canton ?: "", it.phone ?: "", it.nextAction ?: "") })
        return listOf(
            Section("Por estado", list.groupBy { it.status.label }.map { "${it.key}: ${it.value.size}" }.ifEmpty { listOf("Sin prospectos guardados.") }, table),
            Section("Por provincia", list.groupBy { it.province ?: "sin dato" }.map { "${it.key}: ${it.value.size}" }.ifEmpty { listOf("—") }),
            Section("Sin próxima acción", list.filter { it.nextAction.isNullOrBlank() && it.status.name !in setOf("CLIENTE", "DESCARTADO") }.map { it.name }.ifEmpty { listOf("Todos tienen próxima acción.") }),
        )
    }

    private suspend fun finance(ctx: SkillContext, from: LocalDate?): List<Section> {
        val tx = ctx.transactions().values().filter { from == null || runCatching { !LocalDate.parse(it.date).isBefore(from) }.getOrDefault(false) }
        val table = Table(listOf("fecha", "tipo", "categoría", "descripción", "monto", "moneda"), tx.sortedBy { it.date }.map { listOf(it.date, if (it.type == "income") "ingreso" else "gasto", it.category, it.description, it.amount, it.currency) })
        val totals = tx.groupBy { it.currency }.map { (cur, l) ->
            val inc = l.filter { it.type == "income" }.sumOf { BigDecimal(it.amount) }
            val exp = l.filter { it.type == "expense" }.sumOf { BigDecimal(it.amount) }
            "$cur — ingresos ${Money.format(inc, cur)}, gastos ${Money.format(exp, cur)}, balance ${Money.format(inc - exp, cur)}"
        }
        val byCat = tx.filter { it.type == "expense" }.groupBy { it.category to it.currency }.map { (k, l) -> k to l.sumOf { BigDecimal(it.amount) } }
            .sortedByDescending { it.second }.map { (k, v) -> "${k.first}: ${Money.format(v, k.second)}" }
        return listOf(
            Section("Totales por moneda", totals.ifEmpty { listOf("Sin movimientos en el periodo.") }, table),
            Section("Gastos por categoría", byCat.ifEmpty { listOf("—") }),
        )
    }

    private suspend fun inventory(ctx: SkillContext): List<Section> {
        val items = ctx.inventory().all()
        val table = Table(listOf("id", "ámbito", "nombre", "cantidad", "unidad", "mínimo", "costo unitario", "moneda", "ubicación"), items.map { (id, i) -> listOf(id, if (i.scope == "home") "hogar" else "negocio", i.name, i.quantity.toString(), i.unit, i.minQuantity?.toString() ?: "", i.unitCost ?: "", i.currency ?: "", i.location ?: "") })
        val low = items.filter { (_, i) -> i.minQuantity != null && i.quantity <= i.minQuantity }.map { (_, i) -> "${i.name}: ${i.quantity} ${i.unit} (mínimo ${i.minQuantity})" }
        val value = items.filter { it.second.unitCost != null && it.second.currency != null }.groupBy { it.second.currency!! }
            .map { (cur, l) -> "Valor ${Money.format(l.sumOf { BigDecimal(it.second.unitCost!!) * BigDecimal(it.second.quantity) }, cur)}" }
        return listOf(
            Section("Resumen", listOf("Artículos: ${items.size}") + value, table),
            Section("Existencias bajas", low.ifEmpty { listOf("Ninguna.") }),
        )
    }

    private suspend fun crm(ctx: SkillContext, from: LocalDate?): List<Section> {
        val list = ctx.interactions().values().filter { from == null || runCatching { !LocalDate.parse(it.date.take(10)).isBefore(from) }.getOrDefault(false) }
        val table = Table(listOf("fecha", "contacto", "tipo", "resumen", "seguimiento"), list.map { listOf(it.date, it.contactName, it.kind, it.summary, it.followUp ?: "") })
        return listOf(
            Section("Interacciones", list.sortedByDescending { it.date }.map { "${it.date.take(10)} · ${it.contactName} · ${it.kind}: ${it.summary}" }.ifEmpty { listOf("Sin interacciones registradas en el periodo.") }, table),
            Section("Seguimientos pendientes", list.filter { it.followUp != null }.map { "${it.contactName}: ${it.followUp}" }.ifEmpty { listOf("—") }),
        )
    }

    private fun custom(input: JsonObject): List<Section>? {
        val rows = input.arr("rows")?.mapNotNull { it as? JsonObject }?.takeIf { it.isNotEmpty() } ?: return null
        val headers = rows.flatMap { it.keys }.distinct()
        val table = Table(headers, rows.map { r -> headers.map { h -> (r[h] as? JsonPrimitive)?.content ?: r[h]?.toString().orEmpty() } })
        return listOf(Section("Datos", rows.take(50).map { r -> headers.joinToString(" · ") { h -> "$h: ${(r[h] as? JsonPrimitive)?.content ?: r[h] ?: ""}" } }, table))
    }

    private fun markdown(title: String, date: String, sections: List<Section>) = buildString {
        append("# ").append(title).append("\n\n_Generado por JARVIS el ").append(date).append("_\n")
        for (s in sections) {
            append("\n## ").append(s.heading).append("\n\n")
            s.lines.forEach { append("- ").append(it).append('\n') }
            s.table?.takeIf { it.rows.isNotEmpty() }?.let { t ->
                append("\n| ").append(t.headers.joinToString(" | ")).append(" |\n|").append(t.headers.joinToString("|") { "---" }).append("|\n")
                t.rows.take(500).forEach { r -> append("| ").append(r.joinToString(" | ") { it.replace("|", "\\|") }).append(" |\n") }
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun html(title: String, date: String, sections: List<Section>) = buildString {
        append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
        append(esc(title)).append("</title><style>body{font-family:system-ui,sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#1b1b1b}")
        append("table{border-collapse:collapse;width:100%;margin:12px 0}td,th{border:1px solid #ccc;padding:4px 8px;text-align:left}th{background:#f3e6d6}h1{color:#b35a00}</style></head><body>")
        append("<h1>").append(esc(title)).append("</h1><p><em>Generado por JARVIS el ").append(date).append("</em></p>")
        for (s in sections) {
            append("<h2>").append(esc(s.heading)).append("</h2><ul>")
            s.lines.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ul>")
            s.table?.takeIf { it.rows.isNotEmpty() }?.let { t ->
                append("<table><tr>").append(t.headers.joinToString("") { "<th>${esc(it)}</th>" }).append("</tr>")
                t.rows.take(500).forEach { r -> append("<tr>").append(r.joinToString("") { "<td>${esc(it)}</td>" }).append("</tr>") }
                append("</table>")
            }
        }
        append("</body></html>")
    }
}

// ============================================================ development_copilot

class DevelopmentCopilotSkill : Skill {
    override val id = "development_copilot"

    private val secretPatterns = listOf(
        Regex("sk-[A-Za-z0-9_-]{20,}") to "Posible clave de OpenAI",
        Regex("AKIA[0-9A-Z]{16}") to "Posible clave de AWS",
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----") to "Clave privada incrustada",
        Regex("(?i)(api[_-]?key|secret|token|password|contraseña)\\s*[:=]\\s*[\"'][^\"']{8,}[\"']") to "Credencial escrita en el código",
        Regex("gh[pousr]_[A-Za-z0-9]{30,}") to "Posible token de GitHub",
    )

    fun detectLanguage(code: String, hint: String?): String {
        hint?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        return when {
            Regex("^\\s*(fun |val |var |data class |package [a-z])", RegexOption.MULTILINE).containsMatchIn(code) -> "kotlin"
            Regex("^\\s*(def |import [a-z_]+$|from [a-z_.]+ import )", RegexOption.MULTILINE).containsMatchIn(code) -> "python"
            Regex("public (static )?(class|void)|System\\.out").containsMatchIn(code) -> "java"
            Regex("\\binterface \\w+ \\{|: (string|number|boolean)\\b").containsMatchIn(code) -> "typescript"
            Regex("\\b(const|let) \\w+ =|function \\w*\\(|=> ").containsMatchIn(code) -> "javascript"
            Regex("(?i)^\\s*(select|insert|update|create table)\\b", RegexOption.MULTILINE).containsMatchIn(code) -> "sql"
            code.trimStart().startsWith("#!") || Regex("^\\s*(echo|export|if \\[)", RegexOption.MULTILINE).containsMatchIn(code) -> "shell"
            Regex("(?i)<(html|div|body)\\b").containsMatchIn(code) -> "html"
            else -> "desconocido"
        }
    }

    fun scan(code: String): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        code.lines().forEachIndexed { i, line ->
            val n = i + 1
            secretPatterns.forEach { (re, label) -> if (re.containsMatchIn(line)) out += mapOf("type" to "seguridad", "line" to n, "message" to "$label. Muévela a variables de entorno o a un gestor de secretos.") }
            if (Regex("(?i)\\b(TODO|FIXME|HACK)\\b").containsMatchIn(line)) out += mapOf("type" to "pendiente", "line" to n, "message" to line.trim().take(100))
            if (Regex("\\beval\\s*\\(|Runtime\\.getRuntime\\(\\)\\.exec|os\\.system\\(|subprocess\\.call\\(.*shell=True").containsMatchIn(line)) out += mapOf("type" to "seguridad", "line" to n, "message" to "Ejecución dinámica de código o comandos: revisa que la entrada no venga del usuario.")
            if (Regex("(?i)\"\\s*(select|insert|update|delete)\\b[^\"]*\"\\s*\\+").containsMatchIn(line)) out += mapOf("type" to "seguridad", "line" to n, "message" to "SQL construido concatenando texto: usa consultas parametrizadas.")
            if (Regex("http://(?!localhost|127\\.0\\.0\\.1|10\\.|192\\.168\\.)").containsMatchIn(line)) out += mapOf("type" to "seguridad", "line" to n, "message" to "URL sin HTTPS.")
            if (line.length > 160) out += mapOf("type" to "estilo", "line" to n, "message" to "Línea de ${line.length} caracteres.")
        }
        return out
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val code = input.str("code")!!
        val task = input.str("task") ?: "explain"
        val lang = detectLanguage(code, input.str("language"))
        val findings = scan(code)
        val warnings = mutableListOf<String>()
        val hasSecrets = secretPatterns.any { (re, _) -> re.containsMatchIn(code) }
        var answer: String? = null
        if (task != "scan") {
            if (hasSecrets) {
                warnings += "El código parece contener secretos: no lo envío al servidor. Quítalos y vuelve a pedirlo."
            } else {
                val instruction = when (task) {
                    "review" -> "Revisa este código ($lang): errores, riesgos de seguridad, casos límite y mejoras concretas. Sé específico con números de línea."
                    "improve" -> "Propón una versión mejorada de este código ($lang) y explica brevemente los cambios. No inventes APIs."
                    else -> "Explica qué hace este código ($lang), paso a paso y en lenguaje sencillo."
                } + (input.str("question")?.let { " Pregunta del usuario: $it" } ?: "")
                answer = ctx.tryModel(instruction, code, warnings, "La explicación/revisión con IA", AssistantMode.DEEP)
            }
        }
        val data = jsonOf("language" to lang, "lines" to code.lines().size, "findings" to findings, "answer" to answer, "used_model" to (answer != null))
        val sec = findings.count { it["type"] == "seguridad" }
        val summary = "Código $lang de ${code.lines().size} líneas: ${findings.size} observaciones" + (if (sec > 0) ", $sec de seguridad" else "") + "." + (answer?.let { " Incluyo la ${if (task == "review") "revisión" else if (task == "improve") "propuesta" else "explicación"}." } ?: "")
        val cards = listOf(ResultCard.Info("Análisis de código ($lang)", findings.take(10).map { "L${it["line"]}: ${it["message"]}" } + listOfNotNull(answer)))
        return if (task != "scan" && answer == null) Outcome.partial(summary, data, warnings, cards = cards) else Outcome.done(summary, data, warnings = warnings, cards = cards)
    }
}
