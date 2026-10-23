package com.example.jarvis.core.intent

import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.memory.MemoryCommands
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.text.TextNormalizer
import com.example.jarvis.core.tools.WorkingContext
import com.example.jarvis.core.tools.local.NaturalDateParser
import com.example.jarvis.core.tools.local.UnitConverter
import com.example.jarvis.core.util.TimeSource
import kotlinx.serialization.json.JsonObject

/** Resultado del análisis local de una orden. */
sealed interface Intent {
    /** Ejecutar directamente una herramienta (ruta rápida, sin modelo). */
    data class Tool(val name: String, val args: JsonObject, val confidence: Double, val spokenHint: String? = null) : Intent

    /** Falta un dato imprescindible; preguntar al usuario. */
    data class Clarify(val question: String) : Intent

    /** La petición necesita razonamiento del modelo (con la estrategia sugerida). */
    data class NeedsModel(val mode: AssistantMode, val fallback: Tool? = null) : Intent
}

/**
 * Detector de intenciones en español basado en reglas. Cubre las órdenes frecuentes y con
 * estructura clara; el resto se delega al modelo. Chat y voz pasan por aquí por igual.
 */
class IntentDetector(private val time: TimeSource, private val defaultLocation: () -> String? = { null }) {

    private val businessStems = listOf(
        "supermercad", "super", "minisuper", "pulperi", "abastecedor", "ferreter", "tienda", "distribuid", "importad",
        "exportad", "mayorist", "restaurant", "soda", "hotel", "hostal", "farmaci", "empresa", "negocio", "comercio",
        "proveedor", "fabricant", "fabrica", "panader", "carnicer", "verduler", "licorer", "taller", "repuesto", "banco",
        "clinica", "dentista", "gimnasio", "barberi", "salon", "gasoliner", "bodega", "almacen", "cafeteri", "bar",
        "libreri", "papeleri", "zapater", "muebleri", "veterinari", "agroveterinari", "electronic", "celular", "boutique",
        "industria", "oficina", "local", "comercial", "consultorio", "marisqueri", "pizzeri",
    )

    private val ordinals = mapOf(
        "primero" to 0, "primera" to 0, "1" to 0, "segundo" to 1, "segunda" to 1, "2" to 1, "tercero" to 2, "tercera" to 2,
        "tercer" to 2, "3" to 2, "cuarto" to 3, "cuarta" to 3, "4" to 3, "quinto" to 4, "quinta" to 4, "5" to 4,
        "sexto" to 5, "sexta" to 5, "6" to 5, "septimo" to 6, "septima" to 6, "7" to 6, "octavo" to 7, "octava" to 7,
        "8" to 7, "noveno" to 8, "novena" to 8, "9" to 8, "decimo" to 9, "decima" to 9, "10" to 9,
    )

    fun detect(rawText: String, working: WorkingContext): Intent? {
        val original = TextNormalizer.stripWakeWord(rawText)
        val c = TextNormalizer.canonical(rawText)
        if (c.isBlank()) return null

        MemoryCommands.parseRemember(original)?.let {
            return Intent.Tool("remember", jsonOf("content" to it.content, "kind" to it.kind.name, "private" to it.isPrivate), 0.95)
        }
        recall(c)?.let { return it }
        deepResearch(c)?.let { return it }
        dateTime(c)?.let { return it }
        conversion(c)?.let { return it }
        calculation(c, original)?.let { return it }
        deviceStatus(c)?.let { return it }
        weather(c, original)?.let { return it }
        mapAndContactActions(c, working)?.let { return it }
        prospects(c, working)?.let { return it }
        tasks(c, original)?.let { return it }
        notes(c, original)?.let { return it }
        webSearch(c, original)?.let { return it }
        businessSearch(c, original, working)?.let { return it }
        openApp(c, original)?.let { return it }
        return null
    }

    /** Estrategia para peticiones que van al modelo. */
    fun suggestMode(text: String): AssistantMode {
        val c = TextNormalizer.canonical(text)
        val words = c.split(' ').size
        return when {
            deepRe.containsMatchIn(c) || words > 30 -> AssistantMode.DEEP
            words <= 6 && !Regex("\\b(busca|investiga|precio|noticias|actual)").containsMatchIn(c) -> AssistantMode.FAST
            else -> AssistantMode.BALANCED
        }
    }

    // ---------- reglas ----------

    private val deepRe = Regex("\\b(investiga|investigar|investigacion|compara|comparar|comparacion|analiza|analisis|a fondo|en detalle|requisitos|importar|importacion|exportar|exportacion|aranceles|arancel|impuestos de importacion|proveedores de|fabricantes de|estudio de mercado|que puedan comprar|potenciales compradores|clientes potenciales para)\\b")

    private fun deepResearch(c: String): Intent? {
        if (!deepRe.containsMatchIn(c)) return null
        val query = c.replace(Regex("^(investiga|investigar|analiza|averigua)( sobre| acerca de| a fondo)?\\s*"), "").trim()
        val fallback = Intent.Tool("web_search", jsonOf("query" to query.ifBlank { c }, "max_results" to 8), 0.6)
        return Intent.NeedsModel(AssistantMode.DEEP, fallback)
    }

    private fun recall(c: String): Intent? {
        val m = Regex("^(?:que (?:recuerdas|sabes|tienes guardado) (?:de|sobre|acerca de)|cual es mi preferencia (?:de|sobre|para)|que preferencia tengo (?:de|sobre|para)|recuerdas (?:algo )?(?:de|sobre))\\s+(.+)$").find(c)
            ?: Regex("^(?:que recuerdas|que sabes de mi|que recuerdas de mi|cuales son mis preferencias)$").find(c)?.let {
                return Intent.Tool("recall", jsonOf("query" to "preferencias usuario prefiero", "limit" to 10), 0.85)
            }
            ?: return null
        return Intent.Tool("recall", jsonOf("query" to m.groupValues[1], "limit" to 5), 0.85)
    }

    private fun dateTime(c: String): Intent? {
        val hit = Regex("^(?:dime |me dices )?(?:que hora es|que horas son|la hora|hora actual|que dia es(?: hoy)?|que fecha es(?: hoy)?|a que fecha estamos|en que dia estamos|fecha de hoy|dia de hoy)$").containsMatchIn(c)
        return if (hit) Intent.Tool("get_datetime", JsonObject(emptyMap()), 0.98) else null
    }

    private val number = "(-?\\d+(?:[.,]\\d+)?)"

    private fun conversion(c: String): Intent? {
        Regex("^(?:convierte|convertir|pasa|pasame|transforma|cuanto(?:s|as)? (?:es|son|equivale(?:n)? a)?)?\\s*$number\\s*([a-z°²³/0-9 ]+?)\\s+(?:a|en)\\s+([a-z°²³/0-9 ]+?)$").find(c)?.let { m ->
            val (v, from, to) = Triple(m.groupValues[1].replace(',', '.').toDouble(), m.groupValues[2].trim(), m.groupValues[3].trim())
            if (UnitConverter.knows(from) && UnitConverter.knows(to)) {
                return Intent.Tool("convert_units", jsonOf("value" to v, "from" to from, "to" to to), 0.95)
            }
        }
        Regex("^cuant[oa]s? ([a-z°²³/ ]+?) (?:son|hay en|equivalen a|tiene|tienen) $number\\s*([a-z°²³/ ]+)$").find(c)?.let { m ->
            val to = m.groupValues[1].trim()
            val from = m.groupValues[3].trim()
            if (UnitConverter.knows(from) && UnitConverter.knows(to)) {
                return Intent.Tool("convert_units", jsonOf("value" to m.groupValues[2].replace(',', '.').toDouble(), "from" to from, "to" to to), 0.9)
            }
        }
        return null
    }

    private fun calculation(c: String, original: String): Intent? {
        val explicit = Regex("^(cuanto es|cuanto da|cuanto son|calcula|calculame|resultado de|raiz cuadrada de|cual es el resultado de)\\b").containsMatchIn(c)
        val pureMath = Regex("^[\\d\\s+\\-*/^().,%x×÷]+$").matches(c) && Regex("\\d\\s*[+\\-*/^x×÷%]\\s*\\d").containsMatchIn(c)
        val wordsMath = Regex("^\\d+(?:[.,]\\d+)? (mas|menos|por|entre|dividido entre|elevado a) \\d").containsMatchIn(c) ||
            Regex("^\\d+(?:[.,]\\d+)?\\s*(?:%|por ciento) de \\d").containsMatchIn(c)
        if (!explicit && !pureMath && !wordsMath) return null
        if (!Regex("\\d").containsMatchIn(c)) return null
        return Intent.Tool("calculate", jsonOf("expression" to original.trim()), 0.93)
    }

    private fun deviceStatus(c: String): Intent? {
        val aspects = mutableListOf<String>()
        if (Regex("\\b(bateria|carga del (telefono|celular|movil)|cuanta carga|nivel de carga)\\b").containsMatchIn(c)) aspects += "battery"
        if (Regex("\\b(internet|conexion|conectado|wifi|wi fi|datos moviles|red)\\b").containsMatchIn(c) &&
            Regex("^(hay|tengo|estoy|estado|como esta|que|cual|revisa|comprueba|verifica)|\\?|conectado").containsMatchIn(c) &&
            !Regex("\\bbusca").containsMatchIn(c)
        ) aspects += "network"
        if (Regex("\\b(estado del sistema|diagnostico|estado general|como estan los sistemas|informe del sistema|que telefono tengo|modelo del telefono|version de android)\\b").containsMatchIn(c)) {
            aspects += listOf("battery", "network", "device", "backend")
        }
        if (aspects.isEmpty()) return null
        return Intent.Tool("get_device_status", jsonOf("aspects" to aspects.distinct()), 0.9)
    }

    private fun weather(c: String, original: String): Intent? {
        if (!Regex("\\b(clima|que tiempo hace|temperatura|va a llover|esta lloviendo|llueve|pronostico)\\b").containsMatchIn(c)) return null
        if (Regex("\\b(temperatura (de|del) (agua|cuerpo|horno|motor)|convierte)\\b").containsMatchIn(c)) return null
        val place = Regex("\\b(?:en|de) ([a-zñ ]{3,40})$").find(c)?.groupValues?.get(1)?.trim()
            ?.takeIf { it !in setOf("hoy", "ahora", "la manana", "la tarde", "la noche") }
        val originalPlace = place?.let { p -> Regex("(?i)\\b(?:en|de)\\s+(.{3,40})$").find(original)?.groupValues?.get(1)?.trim()?.trimEnd('?', '.', '!') ?: p }
        return Intent.Tool("get_weather", if (originalPlace != null) jsonOf("place" to originalPlace) else JsonObject(emptyMap()), 0.9)
    }

    private fun ordinalIn(c: String): Int? {
        Regex("\\b(?:el|la|al|del|numero|#)\\s*(primero|primera|segundo|segunda|tercero|tercera|tercer|cuarto|cuarta|quinto|quinta|sexto|sexta|septimo|septima|octavo|octava|noveno|novena|decimo|decima|\\d{1,2})\\b").find(c)?.let {
            return ordinals[it.groupValues[1]] ?: it.groupValues[1].toIntOrNull()?.minus(1)
        }
        if (Regex("\\b(el|la|al) ultim[oa]\\b").containsMatchIn(c)) return -1
        return null
    }

    /** Resuelve "el segundo", "ese", o un nombre de la última lista de negocios. */
    private fun referencedBusiness(c: String, working: WorkingContext): String? {
        val list = working.lastBusinesses()
        ordinalIn(c)?.let { idx -> return (if (idx == -1) list.lastOrNull() else list.getOrNull(idx))?.id }
        val byName = list.firstOrNull { b ->
            val n = b.name.value?.let { TextNormalizer.fold(it) } ?: return@firstOrNull false
            n.length >= 4 && c.contains(n)
        }
        if (byName != null) return byName.id
        if (Regex("\\b(ese|esa|este|esta|dicho|dicha)\\b").containsMatchIn(c)) return working.selectedBusinessId ?: list.singleOrNull()?.id
        return null
    }

    private fun mapAndContactActions(c: String, working: WorkingContext): Intent? {
        val wantsMap = Regex("\\b(en el mapa|en un mapa|abre el mapa|abrir el mapa|ver el mapa|ver mapa|muestra(me)? el mapa|mapa de (esos|estos|los))\\b").containsMatchIn(c)
        if (wantsMap) {
            val ref = referencedBusiness(c, working)
            val ids = if (ref != null && ordinalIn(c) != null) listOf(ref) else working.lastBusinesses().map { it.id }
            return Intent.Tool("show_on_map", jsonOf("business_ids" to ids), 0.95)
        }
        if (Regex("\\b(ruta|como llego|llevame|como ir|indicaciones para llegar|navega)\\b").containsMatchIn(c)) {
            val id = referencedBusiness(c, working) ?: return if (working.lastBusinesses().isEmpty()) null else Intent.Clarify("¿Hacia cuál de los negocios quieres la ruta, jefe? Puedes decir \"el primero\", \"el segundo\"…")
            return Intent.Tool("get_directions", jsonOf("business_id" to id), 0.9)
        }
        if (Regex("^(donde queda|donde esta|donde se ubica|ubicacion de|abre la ubicacion)").containsMatchIn(c)) {
            val id = referencedBusiness(c, working) ?: return null
            return Intent.Tool("open_location", jsonOf("business_id" to id), 0.9)
        }
        if (Regex("^(llama|llamar|marca|marcale|llamale)\\b").containsMatchIn(c)) {
            val id = referencedBusiness(c, working) ?: return null
            return Intent.Tool("call_phone", jsonOf("business_id" to id), 0.9)
        }
        if (Regex("\\b(guarda|guardar|agrega|anade|añade|salva)\\b.*\\b(prospecto|cliente potencial|prospectos)\\b").containsMatchIn(c)) {
            val id = referencedBusiness(c, working) ?: return if (working.lastBusinesses().isEmpty()) null else Intent.Clarify("¿Cuál de los negocios guardo como prospecto, jefe?")
            return Intent.Tool("save_prospect", jsonOf("business_id" to id), 0.9)
        }
        if (Regex("^(verifica|comprueba|confirma|contrasta)\\b.*\\b(datos|telefono|correo|informacion|negocio)").containsMatchIn(c)) {
            val id = referencedBusiness(c, working) ?: return null
            return Intent.Tool("business_verify", jsonOf("id" to id), 0.85)
        }
        return null
    }

    private fun prospects(c: String, working: WorkingContext): Intent? {
        Regex("\\borganiza\\w* (?:estos |los |mis |esos )?(?:prospectos|clientes potenciales|negocios guardados) por (ubicacion|provincia|canton|categoria|estado|zona)\\b").find(c)?.let {
            val by = when (it.groupValues[1]) { "provincia" -> "province"; "categoria" -> "category"; "estado" -> "status"; else -> "canton" }
            return Intent.Tool("organize_prospects", jsonOf("by" to by), 0.95)
        }
        if (Regex("^(?:muestra(?:me)? |ver |lista(?:me)? |cuales son )?(?:mis |los )?(?:prospectos|clientes potenciales)(?: guardados)?$").containsMatchIn(c)) {
            return Intent.Tool("list_prospects", JsonObject(emptyMap()), 0.9)
        }
        return null
    }

    private val datePhrase = Regex("(?i)\\b(pasado mañana|pasado manana|mañana|manana|hoy|esta noche|esta tarde|esta mañana|esta manana|el (lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)|(el )?\\d{1,2} de [a-záéíóú]+( de \\d{4})?|\\d{1,2}/\\d{1,2}(/\\d{2,4})?|en (\\d+|un|una|media) (minutos?|horas?|d[ií]as?|semanas?)|a las? \\d{1,2}(:\\d{2}| y media| y cuarto)?\\s*(am|pm|a\\.m\\.|p\\.m\\.|de la mañana|de la manana|de la tarde|de la noche)?|al mediod[ií]a|a medianoche|(por|de) la (mañana|manana|tarde|noche))\\b")

    private fun splitTitleAndDue(text: String): Pair<String, String?> {
        val matches = datePhrase.findAll(text).toList()
        if (matches.isEmpty()) return text.trim() to null
        val due = matches.joinToString(" ") { it.value }
        val title = text.replace(datePhrase, " ").replace(Regex("\\s+"), " ").trim().trim(',', '.', ':').removeSuffix(" para").trim()
        return title to due
    }

    private fun tasks(c: String, original: String): Intent? {
        Regex("(?i)^(?:recu[eé]rdame|av[ií]same|haz que me acuerde de|ponme un recordatorio(?: para)?|crea un recordatorio(?: para)?)\\s+(?:que\\s+|de\\s+)?(.+)$").find(original)?.let { m ->
            val (title, due) = splitTitleAndDue(m.groupValues[1])
            if (due == null) return Intent.Clarify("¿Cuándo quieres que te lo recuerde, jefe? Por ejemplo: \"mañana a las 9\".")
            if (NaturalDateParser.parse(due, time.zonedNow()) == null) return Intent.Clarify("No entendí la fecha \"$due\". ¿Puedes decirla de otra forma?")
            return Intent.Tool("create_task", jsonOf("title" to title.ifBlank { "Recordatorio" }.replaceFirstChar { it.uppercase() }, "due" to due, "remind" to true), 0.92)
        }
        Regex("(?i)^(?:crea|crear|anota|agrega|añade|anade|nueva|apunta)(?: una| la)? (?:tarea|pendiente)(?: de| para| que diga)?:?\\s+(.+)$").find(original)?.let { m ->
            val (title, due) = splitTitleAndDue(m.groupValues[1])
            val args = mutableMapOf<String, Any?>("title" to title.replaceFirstChar { it.uppercase() })
            if (due != null) args["due"] = due
            if (Regex("\\b(urgente|importante|prioridad alta)\\b").containsMatchIn(c)) args["priority"] = "HIGH"
            return Intent.Tool("create_task", jsonOf(*args.toList().toTypedArray()), 0.9)
        }
        if (Regex("\\b(que tengo (para )?hoy|planifica(r)? (mi |el )?dia|plan del dia|agenda de hoy|organiza(r)? mi dia|como esta mi dia)\\b").containsMatchIn(c)) {
            return Intent.Tool("plan_day", JsonObject(emptyMap()), 0.9)
        }
        val filter = when {
            Regex("\\btareas? (vencidas|atrasadas|pendientes vencidas)\\b").containsMatchIn(c) -> "overdue"
            Regex("\\btareas? (completadas|hechas|terminadas)\\b").containsMatchIn(c) -> "done"
            Regex("\\btareas? (de|para) hoy\\b").containsMatchIn(c) -> "today"
            Regex("^(?:cuales son |muestra(?:me)? |ver |lista(?:me)? |que )?(?:mis |las )?(?:tareas|pendientes)(?: tengo)?(?: pendientes)?$").containsMatchIn(c) -> "pending"
            else -> null
        } ?: run {
            Regex("^(?:completa|termina|marca como (?:hecha|completada)) (?:la )?tarea (?:numero |#)?(\\d+)$").find(c)?.let {
                return Intent.Tool("complete_task", jsonOf("task_id" to it.groupValues[1].toInt()), 0.9)
            }
            return null
        }
        return Intent.Tool("list_tasks", jsonOf("filter" to filter), 0.92)
    }

    private fun notes(c: String, original: String): Intent? {
        val m = Regex("(?i)^(?:busca|buscar|consulta|revisa)(?: en)? (?:mis notas|mis apuntes|obsidian|mi boveda|mi bóveda|la boveda|la bóveda)(?: sobre| de| acerca de| por)?\\s+(.+)$").find(original)
            ?: Regex("(?i)^qu[eé] dicen mis notas (?:sobre|de|acerca de)\\s+(.+)$").find(original)
            ?: return null
        return Intent.Tool("search_notes", jsonOf("query" to m.groupValues[1].trim().trimEnd('?')), 0.92)
    }

    private fun webSearch(c: String, original: String): Intent? {
        val m = Regex("(?i)^(?:busca|buscar|búscame|buscame|consulta|averigua)(?: en)? (?:internet|la web|google|la red|línea|linea)(?: sobre| acerca de| de| por| informaci[oó]n (?:sobre|de))?:?\\s+(.+)$").find(original)
            ?: Regex("(?i)^(?:busca|buscar|búscame|buscame|averigua)(?: informaci[oó]n| noticias| datos)(?: sobre| de| acerca de)\\s+(.+)$").find(original)
            ?: return null
        val q = m.groupValues[1].trim().trimEnd('?', '.')
        return Intent.Tool("web_search", jsonOf("query" to q, "max_results" to 8), 0.9)
    }

    private fun businessSearch(c: String, original: String, working: WorkingContext): Intent? {
        if (Regex("^(muestra(me)?|dame|ver) (mas|los siguientes|otros|la siguiente pagina)").containsMatchIn(c)) {
            val last = working.lastBusinessSearch ?: return null
            val next = last.nextOffset ?: return Intent.Clarify("Ya te mostré todos los resultados de esa búsqueda (${last.total}).")
            val args = mutableMapOf<String, Any?>("category" to (last.query.category ?: last.category.label), "offset" to next, "limit" to last.limit)
            last.query.location?.let { args["location"] = it }
            return Intent.Tool("business_search", jsonOf(*args.toList().toTypedArray()), 0.9)
        }
        val m = Regex("^(?:busca|buscar|buscame|encuentra|encuentrame|localiza|localizame|dame|lista|listame|muestrame|quiero|necesito|hay)(?: una lista de| un listado de| los| las| unos| unas| algun| alguna| algunos| algunas)?\\s+(.+)$").find(c)
            ?: return null
        var rest = m.groupValues[1]
        if (businessStems.none { stem -> Regex("\\b$stem").containsMatchIn(rest) }) return null

        val nearMe = Regex("\\b(cerca de mi|cerca de aqui|por aqui|cercanos?|cercanas?|a mi alrededor|en mi zona)\\b").containsMatchIn(rest)
        rest = rest.replace(Regex("\\b(cerca de mi|cerca de aqui|por aqui|a mi alrededor|en mi zona)\\b"), "").trim()
        val locMatch = Regex("^(.+?)\\s+(?:en|de la zona de|en la zona de|cerca de|por|ubicad[oa]s en|dentro de)\\s+(.+)$").find(rest)
        val category = (locMatch?.groupValues?.get(1) ?: rest).replace(Regex("\\b(y organiza.*|y ordena.*|con (sus )?datos.*)$"), "").trim()
        var location = locMatch?.groupValues?.get(2)?.replace(Regex("\\b(y organiza.*|y ordena.*|con (sus )?datos.*)$"), "")?.trim()
        location = location?.let { loc ->
            Regex("(?i)\\b(?:en|cerca de|por)\\s+(.+)$").find(original)?.groupValues?.get(1)
                ?.replace(Regex("(?i)\\s+y\\s+(organiza|ordena).*$"), "")?.trim()?.trimEnd('.', '?', '!')
                ?.takeIf { TextNormalizer.fold(it).startsWith(loc.take(3)) } ?: loc
        }
        if (category.length < 3) return null
        val args = mutableMapOf<String, Any?>("category" to category, "limit" to 20)
        when {
            nearMe -> args["near_me"] = true
            location != null && location.isNotBlank() -> args["location"] = location
            // Importadores/exportadores/fabricantes suelen buscarse a escala nacional.
            Regex("\\b(importad|exportad|fabricant|fabrica|industria)").containsMatchIn(category) -> args["location"] = "Costa Rica"
            defaultLocation() != null -> args["location"] = defaultLocation()
            else -> return Intent.Clarify("¿En qué zona busco $category, jefe?")
        }
        return Intent.Tool("business_search", jsonOf(*args.toList().toTypedArray()), 0.9)
    }

    private fun openApp(c: String, original: String): Intent? {
        val m = Regex("(?i)^(?:abre|abrir|ábreme|abreme|inicia|lanza|ejecuta|entra a|entra en)\\s+(?:la app(?:licaci[oó]n)? (?:de )?|el |la )?(.{2,40})$").find(original) ?: return null
        val app = m.groupValues[1].trim().trimEnd('.', '!', '?')
        if (Regex("\\b(ubicaci|mapa|ruta|chat|tarea|ajuste de jarvis)").containsMatchIn(TextNormalizer.fold(app))) return null
        return Intent.Tool("open_app", jsonOf("app" to app), 0.88)
    }
}
