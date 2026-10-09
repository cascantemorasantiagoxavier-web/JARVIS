package com.example.jarvis.ai.tools

import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class GetDateTimeTool : Tool {
    override val name = "get_date_time"
    override val description = "Obtiene la hora, fecha, día, mes o año actual exacto del sistema local de forma offline"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val rawInput = params["query"]?.toString()?.lowercase() ?: params["type"]?.toString()?.lowercase() ?: ""
        val cal = Calendar.getInstance()
        val localeEs = Locale.Builder().setLanguage("es").setRegion("ES").build()

        val timeFormat12 = SimpleDateFormat("h:mm a", localeEs).format(cal.time)
        val timeFormat24 = SimpleDateFormat("HH:mm", localeEs).format(cal.time)
        val fullDateFormat = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", localeEs).format(cal.time)
        val dayOfWeekFormat = SimpleDateFormat("EEEE", localeEs).format(cal.time)
        val monthFormat = SimpleDateFormat("MMMM", localeEs).format(cal.time)
        val yearFormat = SimpleDateFormat("yyyy", localeEs).format(cal.time)

        val norm = rawInput.replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")

        return when {
            // Combined time & date query: "hora y fecha", "fecha y hora"
            (norm.contains("hora") && norm.contains("fecha")) || norm.contains("completa") -> {
                "Son las $timeFormat12 ($timeFormat24) y hoy es $fullDateFormat."
            }
            // Year query: "año estamos", "que ano es", "ano actual"
            norm.contains("ano") || norm.contains("year") -> {
                "Estamos en el año $yearFormat."
            }
            // Month query: "mes estamos", "que mes es"
            norm.contains("mes") || norm.contains("month") -> {
                "Estamos en el mes de $monthFormat de $yearFormat."
            }
            // Day query: "que dia es hoy", "en que dia estamos"
            (norm.contains("dia") || norm.contains("day")) && !norm.contains("hora") -> {
                "Hoy es $dayOfWeekFormat, $fullDateFormat."
            }
            // Date query: "que fecha es", "fecha de hoy", "dime la fecha"
            norm.contains("fecha") || norm.contains("date") -> {
                "Hoy es $fullDateFormat."
            }
            // Default time query
            else -> {
                "Son las $timeFormat12 ($timeFormat24)."
            }
        }
    }
}
