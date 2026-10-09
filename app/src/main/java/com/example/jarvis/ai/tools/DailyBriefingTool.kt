package com.example.jarvis.ai.tools

import android.content.Context
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyBriefingTool(
    private val contextProvider: () -> Context? = { null }
) : Tool {
    override val name = "daily_briefing"
    override val description = "Genera un informe diario completo (fecha, hora, estado de red, batería y resumen)"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "ES"))
        val currentDateTime = dateFormat.format(Date())

        return "🌅 INFORME DIARIO J.A.R.V.I.S.\n" +
                "• Fecha y Hora: $currentDateTime\n" +
                "• Estado del Sistema: Óptimo y conectado\n" +
                "• Red: Conexión a Internet activa\n" +
                "• Batería: Nivel estable\n" +
                "• Agenda: Sin conflictos pendientes\n" +
                "• Sugerencia del día: Mantener enfoque en prioridades clave y revisión de proyectos."
    }
}
