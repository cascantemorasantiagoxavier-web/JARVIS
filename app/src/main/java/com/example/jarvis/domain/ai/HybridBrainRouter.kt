package com.example.jarvis.domain.ai

enum class RouteType {
    LOCAL_TOOL,
    GEMINI_FAST,
    GEMINI_REASONING,
    WEB_RESEARCH,
    MEMORY,
    OFFLINE_GEMMA,
    ERROR
}

data class RoutingDecision(
    val routeType: RouteType,
    val reasoning: String
)

class HybridBrainRouter {
    fun route(input: String, isOnline: Boolean): RoutingDecision {
        val lower = input.lowercase()
        return when {
            lower.contains("hora") || lower.contains("fecha") || lower.contains("día es hoy") ->
                RoutingDecision(RouteType.LOCAL_TOOL, "Consulta de tiempo/fecha local")
            lower.contains("batería") || lower.contains("bateria") ->
                RoutingDecision(RouteType.LOCAL_TOOL, "Consulta de batería local")
            lower.contains("internet") || lower.contains("conexión") ->
                RoutingDecision(RouteType.LOCAL_TOOL, "Verificación de red local")
            lower.contains("calcula") || lower.contains(" por ") || lower.contains(" más ") || lower.contains(" * ") || lower.contains(" entre ") ->
                RoutingDecision(RouteType.LOCAL_TOOL, "Operación matemática determinista")
            lower.contains("abre ") || lower.contains("abrir ") ->
                RoutingDecision(RouteType.LOCAL_TOOL, "Control de aplicaciones local")
            lower.contains("recuerda") || lower.contains("guardado") || lower.contains("te dije") || lower.contains("preferencia") ->
                RoutingDecision(RouteType.MEMORY, "Consulta o persistencia de memoria")
            lower.contains("presidente") || lower.contains("actual") || lower.contains("hoy") ||
            lower.contains("noticias") || lower.contains("últimas") || lower.contains("ganó") ||
            lower.contains("partido") || lower.contains("clima") || lower.contains("precio") ||
            lower.contains("elecciones") || lower.contains("actualmente") || lower.contains("busca") ->
                RoutingDecision(RouteType.WEB_RESEARCH, "Requiere información actualizada/tiempo real")
            lower == "hola" || lower == "hola jarvis" || lower == "buenos días" || lower == "saludos" ->
                RoutingDecision(RouteType.GEMINI_FAST, "Saludo o conversación simple")
            !isOnline ->
                RoutingDecision(RouteType.OFFLINE_GEMMA, "Sin conexión, usando cerebro local")
            else ->
                RoutingDecision(RouteType.GEMINI_REASONING, "Razonamiento general o conocimiento experto")
        }
    }
}
