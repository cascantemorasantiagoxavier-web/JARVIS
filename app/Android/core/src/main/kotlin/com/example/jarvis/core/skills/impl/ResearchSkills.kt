package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.long
import com.example.jarvis.core.json.str
import com.example.jarvis.core.json.strings
import com.example.jarvis.core.memory.TextRanker
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.text.TextNormalizer
import com.example.jarvis.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.net.URI

internal data class WebHit(val title: String, val url: String, val snippet: String, val publishedAt: String?, val retrievedAt: String?, val query: String) {
    val host: String get() = Web.host(url)
}

internal object Web {
    private val official = listOf(".go.cr", ".gob.", ".gov", ".gub.", "procomer.com", "europa.eu", "who.int", "wto.org", "fao.org", "un.org", ".edu", ".ac.")
    private val marketplaces = listOf("alibaba.", "made-in-china.", "globalsources.", "aliexpress.", "amazon.", "mercadolibre.", "ebay.", "1688.com", "indiamart.", "tradeindia.", "temu.", "walmart.")

    fun host(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
    fun kind(url: String): String {
        val h = host(url).lowercase()
        return when {
            official.any { h.contains(it) || h.endsWith(it.trimStart('.')) } -> "oficial"
            marketplaces.any { h.contains(it) } -> "mercado en línea"
            else -> "otro"
        }
    }

    fun hits(r: ToolResult, query: String): List<WebHit> = (r.data as? JsonObject)?.arr("results")?.map { it.jsonObject }?.map {
        WebHit(it.str("title").orEmpty(), it.str("url").orEmpty(), it.str("snippet").orEmpty(), it.str("published_at")?.takeIf { p -> p != "no disponible" }, (r.data as JsonObject).str("retrieved_at"), query)
    }?.filter { it.url.startsWith("http") }.orEmpty()

    fun source(h: WebHit) = SkillSource(h.title.ifBlank { h.host }, h.url, "web (${kind(h.url)})", h.retrievedAt)

    /** Frases de un texto de terceros relevantes para la consulta (datos, no instrucciones). */
    fun evidence(text: String, query: String, max: Int): List<String> {
        val sentences = SkillText.sentences(text).filter { it.length in 30..600 && !looksLikeInstruction(it) }
        return TextRanker.rank(query, sentences.mapIndexed { i, s -> TextRanker.Doc(i, s) }, max, minScore = 0.05).map { sentences[it.first] }
    }

    /** Frases que intentan dar órdenes al asistente se excluyen de la evidencia. */
    fun looksLikeInstruction(s: String): Boolean = SkillText.containsAny(s, "ignora las instrucciones", "ignora todas las instrucciones", "ignore previous", "ignore all previous", "eres un asistente", "system prompt", "asistente de ia debe", "llama al", "envia un mensaje", "transfiere")
}

/** Búsqueda web vía la herramienta (servidor). Devuelve los resultados o el resultado de error. */
internal suspend fun SkillContext.search(query: String, max: Int, domains: List<String> = emptyList()): Pair<List<WebHit>, ToolResult> {
    progress("BUSCANDO EN LA WEB")
    val r = tool("web_search", jsonOf("query" to query, "max_results" to max, "domains" to domains.ifEmpty { null }))
    return Web.hits(r, query) to r
}

internal suspend fun SkillContext.read(url: String): Pair<String?, ToolResult> {
    progress("LEYENDO FUENTE")
    val r = tool("fetch_page", jsonOf("url" to url))
    return (if (r.ok) (r.data as? JsonObject)?.str("text") else null) to r
}

// ============================================================ business_prospecting

class BusinessProspectingSkill : Skill {
    override val id = "business_prospecting"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        ctx.requireServer("La búsqueda de negocios")?.let { return it }
        val category = input.str("category")!!
        val args = jsonOf("category" to category, "location" to input.str("location"), "near_me" to (input.bool("near_me") == true), "limit" to (input.int("limit") ?: 20))
        ctx.progress("BUSCANDO NEGOCIOS")
        val r = ctx.tool("business_search", args)
        if (!r.ok) return Outcome.fromTool(r, "La búsqueda de negocios")
        val d = r.data as JsonObject
        val results = d.arr("results")?.map { it.jsonObject }.orEmpty()
        val onlyPhone = input.bool("only_with_phone") == true
        fun has(o: JsonObject, k: String) = o.str(k)?.let { it != "no disponible" } ?: false
        val ranked = results
            .map { o -> o to listOf("phone", "website", "email", "address").count { has(o, it) } + if (o["has_coordinates"]?.toString() == "true") 1 else 0 }
            .filter { (o, _) -> !onlyPhone || has(o, "phone") }
            .sortedByDescending { it.second }
        val warnings = mutableListOf<String>()
        val saved = mutableListOf<Map<String, Any?>>()
        val saveTop = input.int("save_top") ?: 0
        for ((o, _) in ranked.take(saveTop)) {
            val s = ctx.tool("save_prospect", jsonOf("business_id" to o.str("id"), "note" to input.str("note")))
            if (s.ok) saved += mapOf("business_id" to o.str("id"), "name" to o.str("name"), "message" to s.message) else warnings += "No se guardó ${o.str("name")}: ${s.message}"
        }
        val data = jsonOf(
            "category" to d.str("category"), "area" to d.str("area"), "total" to d["total"], "source" to d.str("source"), "retrieved_at" to d.str("retrieved_at"),
            "ranked" to ranked.map { (o, score) -> mapOf("id" to o.str("id"), "name" to o.str("name"), "completeness" to score, "phone" to o.str("phone"), "website" to o.str("website"), "email" to o.str("email"), "address" to o.str("address"), "location" to o.str("location")) },
            "saved" to saved, "notice" to "Datos de OpenStreetMap: cada campo muestra su estado; los vacíos no se completan con suposiciones.",
        )
        val msg = r.message + (if (onlyPhone) " Con teléfono publicado: ${ranked.size}." else "") + (if (saved.isNotEmpty()) " Guardé ${saved.size} como prospectos (los más completos)." else "")
        return Outcome.done(msg, data, listOf(SkillSource(d.str("source") ?: "OpenStreetMap", "https://www.openstreetmap.org", "osm (ODbL)", d.str("retrieved_at"))), warnings, r.cards)
    }
}

// ============================================================ deep_research

class DeepResearchSkill : Skill {
    override val id = "deep_research"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        ctx.requireServer("La investigación web")?.let { return it }
        val question = input.str("question")!!
        val depth = input.str("depth") ?: "standard"
        val (nQueries, nPages) = when (depth) { "quick" -> 2 to 2; "thorough" -> 4 to 5; else -> 3 to 3 }
        val domains = input.strings("domains")
        val warnings = mutableListOf<String>()

        // 1) Plan de búsqueda: el modelo propone subconsultas; sin modelo, variantes fijas.
        val planned = ctx.tryModel(
            "Propón ${nQueries - 1} consultas de búsqueda web distintas y concretas para investigar la pregunta. Una por línea, sin numerar, sin comentarios.",
            question, mutableListOf(), "Plan de búsqueda", AssistantMode.FAST,
        )?.lines()?.map { it.trim().trim('-', '•', '"', ' ') }?.filter { it.length in 4..200 }?.take(nQueries - 1)
        val queries = (listOf(question) + (planned ?: listOf("$question datos oficiales", "$question estadísticas recientes"))).distinct().take(nQueries)

        // 2) Búsqueda.
        val hits = mutableListOf<WebHit>()
        for (q in queries) {
            val (h, r) = ctx.search(q, 6, domains)
            if (!r.ok) { if (hits.isEmpty() && q == queries.first()) return Outcome.fromTool(r, "La búsqueda web"); warnings += "Consulta \"$q\": ${r.message}"; continue }
            hits += h
        }
        val unique = hits.distinctBy { it.url.substringBefore('#').trimEnd('/') }
        if (unique.isEmpty()) return Outcome.done("No encontré fuentes para \"$question\".", jsonOf("question" to question, "queries" to queries, "sources" to emptyList<Any>(), "findings" to emptyList<Any>(), "conclusions" to null, "used_model" to false), warnings = warnings)

        // 3) Lectura: primero fuentes oficiales y las más relevantes.
        val ranked = TextRanker.rank(question, unique.map { TextRanker.Doc(it, it.title + " " + it.snippet) }, unique.size, minScore = 0.0).map { it.first }
            .sortedByDescending { if (Web.kind(it.url) == "oficial") 1 else 0 }
        val findings = mutableListOf<Map<String, Any?>>()
        val numbered = ranked.take(10)
        for ((i, h) in numbered.withIndex()) {
            val n = i + 1
            if (i < nPages) {
                val (text, r) = ctx.read(h.url)
                if (text != null) Web.evidence(text, question, 3).forEach { findings += mapOf("source" to n, "text" to it) }
                else warnings += "No se pudo leer ${h.host}: ${r.message}"
            }
            if (h.snippet.isNotBlank() && !Web.looksLikeInstruction(h.snippet)) findings += mapOf("source" to n, "text" to h.snippet, "from" to "fragmento del buscador")
        }

        // 4) Síntesis con citas [n]; sin modelo, solo hallazgos.
        val evidence = findings.joinToString("\n") { "[${it["source"]}] ${it["text"]}" }.take(14_000)
        val conclusions = ctx.tryModel(
            "Con SOLO esta evidencia (numerada por fuente), responde la pregunta \"$question\" en 5-8 frases. Cita cada afirmación con [n]. " +
                "Separa lo confirmado de lo incierto y menciona contradicciones entre fuentes. Si la evidencia no basta, dilo.",
            evidence, warnings, "La síntesis de conclusiones", AssistantMode.DEEP,
        )
        val sources = numbered.mapIndexed { i, h -> mapOf("n" to i + 1, "title" to h.title, "url" to h.url, "kind" to Web.kind(h.url), "published_at" to h.publishedAt) }
        val data = jsonOf(
            "question" to question, "depth" to depth, "queries" to queries, "sources" to sources, "findings" to findings.take(40),
            "conclusions" to conclusions, "used_model" to (conclusions != null),
            "notice" to "Contenido de terceros tratado como datos. Verifica las cifras importantes en la fuente original.",
        )
        val msg = (conclusions?.let { SkillText.truncate(it, 700) } ?: "Reuní ${findings.size} hallazgos de ${sources.size} fuentes sobre \"$question\" (sin síntesis del modelo).")
        val cards = listOf(ResultCard.Info("Investigación: ${SkillText.truncate(question, 60)}", listOfNotNull(conclusions) + sources.take(8).map { "[${it["n"]}] ${it["title"]} — ${it["url"]}" }))
        val src = numbered.map(Web::source)
        return if (conclusions == null) Outcome.partial(msg, data, warnings, src, cards) else Outcome.done(msg, data, src, warnings, cards)
    }
}

// ============================================================ import_export_research

class ImportExportResearchSkill : Skill {
    override val id = "import_export_research"

    private val crOfficial = listOf("hacienda.go.cr", "procomer.com", "ministeriodesalud.go.cr", "meic.go.cr", "sfe.go.cr", "senasa.go.cr", "comex.go.cr")

    /** Partidas del Sistema Armonizado de productos frecuentes en Costa Rica. Son sugerencias: se piden confirmar. */
    private val hsHints = linkedMapOf(
        "aceite de palma" to "1511", "dispositivos medicos" to "9018", "instrumentos medicos" to "9018",
        "cafe" to "0901", "banano" to "0803", "banana" to "0803", "piña" to "080430", "pina" to "080430", "aguacate" to "080440",
        "azucar" to "1701", "cacao" to "1801", "flores" to "0603", "yuca" to "071410",
    )

    private fun suggestHs(product: String): String? {
        val p = " " + TextNormalizer.fold(product) + " "
        return hsHints.entries.firstOrNull { (k, _) -> Regex("\\b${Regex.escape(k)}s?\\b").containsMatchIn(p) }?.value
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        ctx.requireServer("La investigación de comercio exterior")?.let { return it }
        val product = input.str("product")!!
        val direction = input.str("direction") ?: "import"
        val partner = input.str("country") ?: if (direction == "import") "China" else "Estados Unidos"
        val warnings = mutableListOf<String>()
        val givenHs = input.str("hs_code")
        val hsCode = givenHs ?: suggestHs(product)
        val aspects = input.strings("aspects").ifEmpty {
            listOf("manufacturers", "suppliers", "prices", "requirements", "tariffs") + if (hsCode != null) listOf("trade_stats") else emptyList()
        }
        val extraSources = mutableListOf<SkillSource>()
        val all = mutableListOf<WebHit>()
        val sections = linkedMapOf<String, List<Map<String, Any?>>>()
        var firstError: ToolResult? = null

        suspend fun run(aspect: String, queries: List<String>, domains: List<String> = emptyList()): List<WebHit> {
            val out = mutableListOf<WebHit>()
            for (q in queries) {
                val (h, r) = ctx.search(q, 6, domains)
                if (!r.ok) { firstError = firstError ?: r; warnings += "$aspect: ${r.message}" } else out += h
            }
            return out.distinctBy { it.url }.also { all += it }
        }
        fun rows(h: List<WebHit>) = h.take(8).map { mapOf("title" to it.title, "url" to it.url, "kind" to Web.kind(it.url), "snippet" to SkillText.truncate(it.snippet, 240)) }

        if ("manufacturers" in aspects) sections["manufacturers"] = rows(run("Fabricantes", listOf("$product manufacturer $partner", "fabricantes de $product $partner")))
        if ("suppliers" in aspects) sections["suppliers"] = rows(run("Proveedores", listOf("$product wholesale supplier", "proveedores mayoristas de $product Costa Rica")))
        if ("prices" in aspects) {
            val h = run("Precios", listOf("$product price per unit FOB", "precio $product por mayor"))
            sections["prices"] = h.flatMap { hit -> Money.findAll(hit.title + " " + hit.snippet).take(3).map { m -> mapOf("amount" to m.amount.toDouble(), "currency" to m.currency, "formatted" to m.format(), "context" to SkillText.truncate(hit.snippet.ifBlank { hit.title }, 200), "url" to hit.url, "kind" to Web.kind(hit.url)) } }.take(15)
            if (sections["prices"].isNullOrEmpty()) warnings += "No encontré precios publicados en los resultados; pide cotizaciones a los proveedores."
        }
        if ("requirements" in aspects) {
            val q = if (direction == "import") listOf("requisitos importación $product Costa Rica", "permiso importación $product Costa Rica") else listOf("requisitos exportación $product Costa Rica", "exportar $product desde Costa Rica procomer")
            val h = run("Requisitos", q, crOfficial)
            val official = h.filter { Web.kind(it.url) == "oficial" }.take(2)
            val reqs = mutableListOf<Map<String, Any?>>()
            for (o in official) {
                val (text, _) = ctx.read(o.url)
                text?.let { t -> SkillText.sentences(t).filter { s -> SkillText.containsAny(s, "requisit", "permiso", "registro", "certificad", "licencia", "nota tecnica", "autorizacion", "declaracion") && !Web.looksLikeInstruction(s) }.take(6).forEach { reqs += mapOf("text" to SkillText.truncate(it, 300), "url" to o.url, "official" to true) } }
            }
            reqs += h.filter { it !in official }.take(5).map { mapOf("text" to SkillText.truncate(it.snippet.ifBlank { it.title }, 240), "url" to it.url, "official" to (Web.kind(it.url) == "oficial")) }
            sections["requirements"] = reqs
            if (official.isEmpty()) warnings += "No encontré páginas oficiales en esta búsqueda: confirma los requisitos con PROCOMER, Hacienda o el ministerio correspondiente."
        }
        if ("tariffs" in aspects) sections["tariffs"] = rows(run("Aranceles", listOf("arancel $product Costa Rica partida arancelaria", "$product HS code tariff Costa Rica")))
        if ("trade_stats" in aspects) {
            if (hsCode == null) {
                warnings += "Para las estadísticas oficiales de UN Comtrade indica la partida arancelaria (código SA de 4 o 6 dígitos)."
            } else {
                if (givenHs == null) warnings += "Usé la partida SA $hsCode sugerida para «$product»; confírmala en la clasificación arancelaria (TICA de Hacienda o PROCOMER)."
                val r = ctx.tool("trade_statistics", jsonOf("hs_code" to hsCode, "flow" to direction, "by_partner" to true))
                if (r.ok) {
                    val d = r.data as JsonObject
                    val stats = mutableListOf<Map<String, Any?>>()
                    d.arr("totals")?.forEach { t -> val o = t.jsonObject; stats += mapOf("year" to o.int("year"), "value_usd" to o.long("value_usd"), "kind" to "total") }
                    d.arr("top_partners")?.take(8)?.forEach { t -> val o = t.jsonObject; stats += mapOf("partner" to o.str("partner"), "value_usd" to o.long("value_usd"), "kind" to "socio") }
                    sections["trade_stats"] = stats
                    warnings += d.strings("warnings")
                    extraSources += SkillSource("UN Comtrade — partida SA $hsCode", "https://comtradeplus.un.org", "estadística oficial", d.str("retrieved_at") ?: ctx.nowIso())
                } else if (Outcome.isConfigError(r.errorCode)) {
                    warnings += "Estadísticas oficiales no disponibles: el servidor no tiene UN_COMTRADE_API_KEY."
                } else {
                    warnings += "UN Comtrade: ${r.message}"
                }
            }
        }

        if (all.isEmpty() && firstError != null && sections["trade_stats"].isNullOrEmpty()) return Outcome.fromTool(firstError!!, "La investigación")
        val nextSteps = ctx.tryModel(
            "Con estos resultados, propone 5 pasos concretos para ${if (direction == "import") "importar a Costa Rica" else "exportar desde Costa Rica"} \"$product\". Cita las URL. No inventes costos ni requisitos.",
            sections.entries.joinToString("\n") { (k, v) -> "$k:\n" + v.joinToString("\n") { it.toString() } }.take(12_000), warnings, "Los pasos sugeridos con IA",
        )
        warnings += "Los precios son los publicados en la web (pueden estar desactualizados o excluir envío e impuestos); no son cotizaciones."
        val data = jsonOf("product" to product, "direction" to direction, "partner_country" to partner, "hs_code" to hsCode, "hs_code_confirmed" to (givenHs != null), "sections" to sections, "next_steps" to nextSteps, "used_model" to (nextSteps != null))
        val msg = "Investigué ${if (direction == "import") "la importación" else "la exportación"} de $product: " + sections.entries.joinToString(", ") { "${it.value.size} ${label(it.key)}" } + "."
        val cards = listOf(ResultCard.Info("Comercio exterior: $product", sections.flatMap { (k, v) -> listOf("${label(k).replaceFirstChar { it.uppercase() }}:") + v.take(4).map { "  • " + line(it) } } + listOfNotNull(nextSteps)))
        return Outcome.done(msg, data, extraSources + all.distinctBy { it.url }.take(20).map(Web::source), warnings, cards)
    }

    private fun line(it: Map<String, Any?>): String = when (it["kind"]) {
        "total" -> "${it["year"]}: US$${"%,d".format(java.util.Locale.US, it["value_usd"] as Long? ?: 0L)} (UN Comtrade)"
        "socio" -> "${it["partner"]}: US$${"%,d".format(java.util.Locale.US, it["value_usd"] as Long? ?: 0L)}"
        else -> (it["formatted"]?.let { f -> "$f — " } ?: "") + (it["title"] ?: it["text"]) + " (${Web.host(it["url"] as String)})"
    }

    private fun label(k: String) = mapOf("manufacturers" to "fabricantes", "suppliers" to "proveedores", "prices" to "precios publicados", "requirements" to "requisitos", "tariffs" to "referencias arancelarias", "trade_stats" to "estadísticas oficiales (UN Comtrade)")[k] ?: k
}

// ============================================================ price_comparison

class PriceComparisonSkill : Skill {
    override val id = "price_comparison"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        ctx.requireServer("La comparación de precios")?.let { return it }
        val product = input.str("product")!!
        val where = input.str("location") ?: "Costa Rica"
        val maxSources = input.int("max_sources") ?: 6
        val warnings = mutableListOf<String>()
        val hits = mutableListOf<WebHit>()
        for (q in listOf("precio $product $where", "$product precio colones")) {
            val (h, r) = ctx.search(q, 8)
            if (!r.ok) { if (hits.isEmpty()) return Outcome.fromTool(r, "La búsqueda de precios"); warnings += r.message } else hits += h
        }
        val unique = hits.distinctBy { it.url }
        val productTerms = TextNormalizer.searchTokens(product)
        fun relevant(text: String) = TextNormalizer.searchTokens(text).let { t -> productTerms.count { p -> t.any { it.startsWith(p) || p.startsWith(it) } } >= (productTerms.size + 1) / 2 }
        val offers = mutableListOf<Map<String, Any?>>()
        for (h in unique) {
            if (!relevant(h.title + " " + h.snippet)) continue
            var prices = Money.findAll(h.title + " " + h.snippet, defaultCurrency = null)
            if (prices.isEmpty() && offers.size < maxSources && ctx.runs.count { it.name == "fetch_page" } < 3) {
                val (text, _) = ctx.read(h.url)
                prices = text?.let { t -> SkillText.sentences(t).filter { relevant(it) }.flatMap { Money.findAll(it) }.take(2) }.orEmpty()
            }
            prices.firstOrNull()?.let { m -> offers += mapOf("store" to h.host, "title" to h.title, "url" to h.url, "amount" to m.amount.toDouble(), "currency" to m.currency, "formatted" to m.format(), "retrieved_at" to h.retrievedAt) }
            if (offers.size >= maxSources) break
        }
        val stats = offers.groupBy { it["currency"] as String }.map { (cur, l) ->
            val sorted = l.map { BigDecimal(it["amount"].toString()) }.sorted()
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]).divide(BigDecimal(2))
            mapOf("currency" to cur, "count" to l.size, "min" to sorted.first().toDouble(), "max" to sorted.last().toDouble(), "median" to median.toDouble())
        }
        warnings += "Precios publicados en la web: pueden estar desactualizados o no incluir impuestos ni envío. No se convierten monedas."
        val cheapest = stats.associate { s -> s["currency"] to offers.filter { it["currency"] == s["currency"] }.minByOrNull { (it["amount"] as Double) } }
        val data = jsonOf("product" to product, "offers" to offers, "stats" to stats, "cheapest" to cheapest.values.filterNotNull())
        val msg = if (offers.isEmpty()) "No encontré precios publicados para \"$product\". Prueba con un nombre de producto más específico."
        else "Encontré ${offers.size} precios para \"$product\": " + stats.joinToString("; ") { s -> "${s["count"]} en ${s["currency"]}, desde ${Money.format(BigDecimal(s["min"].toString()), s["currency"] as String)} hasta ${Money.format(BigDecimal(s["max"].toString()), s["currency"] as String)}" } + "."
        return Outcome.done(msg, data, offers.map { SkillSource(it["title"] as String, it["url"] as String, "web (tienda)", it["retrieved_at"] as String?) }, warnings,
            listOf(ResultCard.Info("Precios: $product", offers.sortedBy { it["amount"] as Double }.map { "${it["formatted"]} — ${it["store"]}" })))
    }
}
