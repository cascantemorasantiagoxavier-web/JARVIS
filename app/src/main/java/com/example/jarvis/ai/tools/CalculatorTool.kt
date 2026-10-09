package com.example.jarvis.ai.tools

import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool
import java.util.Locale

class CalculatorTool : Tool {
    override val name = "calculator"
    override val description = "Calcula operaciones matemáticas básicas (suma, resta, multiplicación, división, porcentajes) localmente"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val input = params["query"]?.toString()?.lowercase()?.trim() ?: params["title"]?.toString()?.lowercase()?.trim() ?: ""

        if (input.isBlank()) {
            return "Por favor, especifica una operación matemática."
        }

        // Percent calculation: e.g. "15% de 200" or "15 % de 200"
        val percentRegex = """(\d+(?:\.\d+)?)\s*%\s*de\s*(\d+(?:\.\d+)?)""".toRegex()
        val percentMatch = percentRegex.find(input)
        if (percentMatch != null) {
            val (pctStr, totalStr) = percentMatch.destructured
            val pct = pctStr.toDoubleOrNull() ?: 0.0
            val total = totalStr.toDoubleOrNull() ?: 0.0
            val result = (pct / 100.0) * total
            return "El $pct% de $total es ${formatResult(result)}."
        }

        // Multiplication: "25 por 40", "25 x 40", "25 * 40", "multiplica 25 por 40"
        val multRegex = """(\d+(?:\.\d+)?)\s*(?:por|x|\*)\s*(\d+(?:\.\d+)?)""".toRegex()
        val multMatch = multRegex.find(input)
        if (multMatch != null) {
            val (aStr, bStr) = multMatch.destructured
            val a = aStr.toDoubleOrNull() ?: 0.0
            val b = bStr.toDoubleOrNull() ?: 0.0
            return "El resultado de $a × $b es ${formatResult(a * b)}."
        }

        // Division: "100 / 4", "100 dividido entre 4", "100 entre 4"
        val divRegex = """(\d+(?:\.\d+)?)\s*(?:/|dividido entre|dividido por|entre)\s*(\d+(?:\.\d+)?)""".toRegex()
        val divMatch = divRegex.find(input)
        if (divMatch != null) {
            val (aStr, bStr) = divMatch.destructured
            val a = aStr.toDoubleOrNull() ?: 0.0
            val b = bStr.toDoubleOrNull() ?: 0.0
            if (b == 0.0) return "No es posible dividir entre cero."
            return "El resultado de $a ÷ $b es ${formatResult(a / b)}."
        }

        // Addition: "125 + 75", "125 más 75", "suma 125 y 75"
        val addRegex = """(\d+(?:\.\d+)?)\s*(?:\+|más|mas)\s*(\d+(?:\.\d+)?)""".toRegex()
        val addMatch = addRegex.find(input)
        if (addMatch != null) {
            val (aStr, bStr) = addMatch.destructured
            val a = aStr.toDoubleOrNull() ?: 0.0
            val b = bStr.toDoubleOrNull() ?: 0.0
            return "El resultado de $a + $b es ${formatResult(a + b)}."
        }

        // Subtraction: "1000 - 350", "1000 menos 350", "resta 350 a 1000"
        val subRegex = """(\d+(?:\.\d+)?)\s*(?:-|menos)\s*(\d+(?:\.\d+)?)""".toRegex()
        val subMatch = subRegex.find(input)
        if (subMatch != null) {
            val (aStr, bStr) = subMatch.destructured
            val a = aStr.toDoubleOrNull() ?: 0.0
            val b = bStr.toDoubleOrNull() ?: 0.0
            return "El resultado de $a - $b es ${formatResult(a - b)}."
        }

        return "No pude interpretar la operación matemática. Prueba con ejemplos como '25 por 40', '100 / 4' o '15% de 200'."
    }

    private fun formatResult(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }
}
