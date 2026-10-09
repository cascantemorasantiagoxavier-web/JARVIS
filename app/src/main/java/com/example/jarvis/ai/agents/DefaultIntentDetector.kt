package com.example.jarvis.ai.agents

import java.text.Normalizer

fun String.normalizeQuery(): String {
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
    val noDiacritics = "\\p{InCombiningDiacriticalMarks}+".toRegex().replace(normalized, "")
    return noDiacritics.replace(Regex("[^a-z0-9 ]", RegexOption.IGNORE_CASE), "").lowercase().trim()
}

class DefaultIntentDetector : IntentDetector {
    override suspend fun detectIntent(input: String): String {
        val norm = input.normalizeQuery().trim()

        val isSpecialDayKnowledgeQuery = norm.contains("dia del") || norm.contains("dia de la") ||
                norm.contains("dia de los") || norm.contains("dia internacional") ||
                norm.contains("independencia") || norm.contains("semana cae") ||
                norm.contains("celebr") || norm.contains("cumplea") || norm.contains("festiv")

        return when {
            norm.contains("short") || norm.contains("shorts") || norm.contains("crea un video") ||
                    norm.contains("crear short") || norm.contains("haz un short") || norm.contains("publica el short") ||
                    norm.contains("renderiza el short") -> "YOUTUBE_SHORTS"

            (norm.contains("google maps") || norm.contains("en maps") || norm.contains("como llegar") || norm.contains("navegacion") || norm.contains("mapa") || (norm.contains("busca ") && (norm.contains(" en ") || norm.contains("cerca")) && (norm.contains("restaurante") || norm.contains("gasolinera") || norm.contains("supermercado") || norm.contains("farmacia") || norm.contains("hospital")))) -> "MAPS"

            norm.contains("contacto") || norm.contains("contactos") || norm.contains("numero de") || norm.contains("telefono de") -> "CONTACT_SEARCH"

            norm.contains("buenos dias") || norm.contains("informe diario") || norm.contains("resumen del dia") || norm.contains("briefing") || norm.contains("organiza mi dia") -> "DAILY_BRIEFING"

            norm.contains("que tengo pendiente") || norm.contains("lista de tareas") || norm.contains("mis tareas") || norm.contains("tareas pendientes") -> "TASK_LIST"

            norm.contains("marca como completada") || norm.contains("termina la tarea") || norm.contains("completar tarea") || norm.contains("marca como terminada") || norm.contains("marca esta tarea") -> "TASK_COMPLETE"

            norm.contains("recuerdame") || norm.contains("crea una tarea") || norm.contains("nueva tarea") || norm.contains("anota") -> "TASK_CREATE"

            !isSpecialDayKnowledgeQuery && (
                norm == "hora" || norm == "fecha" || norm == "dia" ||
                norm.contains("que hora es") || norm.contains("hora actual") || norm.contains("dime la hora") || norm.contains("dame la hora") ||
                norm.contains("que fecha es hoy") || norm.contains("que dia es hoy") || norm.contains("en que dia estamos") ||
                norm.contains("en que fecha estamos") || norm.contains("fecha actual") || norm.contains("dime la fecha")
            ) -> "DATE_TIME_QUERY"

            norm.contains("bater") || norm.contains("battery") -> "BATTERY_QUERY"

            norm.contains("tengo internet") || norm.contains("hay internet") ||
                    norm.contains("conexion a internet") ||
                    norm.contains("estoy conectado") || norm.contains("estado de la red") -> "NETWORK_QUERY"

            norm.startsWith("calcula ") || norm.contains("cuanto es ") ||
                    "\\b\\d+\\s+por\\s+\\d+\\b".toRegex().containsMatchIn(norm) ||
                    norm.contains(" dividido ") ||
                    norm.contains("% de") -> "CALCULATOR_QUERY"

            norm == "hola" || norm == "hola jarvis" || norm == "buenos dias" ||
                    norm == "buenas tardes" || norm == "hello" || norm == "hi" -> "CONVERSATION"

            norm.contains("ajustes") || norm.contains("settings") ||
                    norm.contains("configuraci") || norm.contains("wifi") ||
                    norm.contains("bluetooth") || norm.contains("sonido") -> "OPEN_SETTINGS"

            norm.contains("modelo del dispositivo") || norm.contains("version android") ||
                    norm.contains("que telefono tengo") -> "DEVICE_INFO"

            (norm.startsWith("abre ") || norm.startsWith("abrir ") || norm.startsWith("open ") || norm.startsWith("launch ") || norm.startsWith("inicia ")) &&
                    !norm.contains("ajustes") && !norm.contains("configuraci") && !norm.contains("maps") -> "OPEN_APP"

            norm.contains("minisuper") || norm.contains("ferreteria") || norm.contains("restaurante") ||
                    norm.contains("farmacia") || norm.contains("negocio") || norm.contains("tienda") ||
                    norm.contains("supermercado") || norm.contains("cerca de") || norm.contains("cerca") -> "WEB_SEARCH"

            norm.contains("busca en internet") || norm.contains("noticias") || norm.contains("presidente actual") ||
                    norm.contains("precio de") || norm.contains("busca ") || norm.contains("encuentra ") || norm.contains("investiga ") -> "WEB_SEARCH"

            norm.contains("recuerda que") || norm.contains("memoriza que") ||
                    norm.contains("guarda que") || norm.contains("no olvides que") ||
                    norm.contains("me llamo ") || norm.contains("mi nombre es ") -> "MEMORY_SAVE"

            norm.contains("olvida que") || norm.contains("borra el recuerdo") -> "MEMORY_DELETE"

            norm.contains("como me llamo") || norm.contains("cual es mi nombre") ||
                    norm.contains("que recuerdas") || norm.contains("busca en memoria") ||
                    norm.contains("que cosas tienes guardadas") -> "MEMORY_QUERY"

            norm.startsWith("que") || norm.startsWith("como") || norm.startsWith("por que") || norm.startsWith("cual") || norm.startsWith("cuando") || norm.startsWith("explica") -> "QUESTION"

            else -> "GENERAL"
        }
    }
}
