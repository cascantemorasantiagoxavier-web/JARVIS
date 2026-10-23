package com.example.jarvis.core.tools.local

import com.example.jarvis.core.text.TextNormalizer
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class CalculationException(message: String) : Exception(message)

/**
 * Calculadora segura (analizador descendente recursivo; no evalúa código).
 * Acepta símbolos y español natural: "25 por 4", "raíz cuadrada de 81", "15% de 200", "2 elevado a 10".
 */
object Calculator {
    fun evaluate(input: String): Double {
        val expr = toSymbols(input)
        if (expr.isBlank()) throw CalculationException("No encontré una expresión para calcular.")
        val p = Parser(expr)
        val v = p.parseExpression()
        p.skipSpaces()
        if (!p.atEnd()) throw CalculationException("No entiendo la expresión cerca de \"${p.rest()}\".")
        if (v.isNaN() || v.isInfinite()) throw CalculationException("El resultado no es un número finito.")
        return v
    }

    fun format(v: Double): String {
        if (v == round(v) && abs(v) < 1e15) return String.format(java.util.Locale.ROOT, "%,d", v.toLong()).replace(',', ' ')
        val bd = BigDecimal(v).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        return bd.toPlainString()
    }

    /** Convierte expresiones en español a símbolos. */
    fun toSymbols(input: String): String {
        var s = " " + TextNormalizer.fold(TextNormalizer.stripWakeWord(input)) + " "
        s = s.replace(Regex("\\b(cuanto es|cuanto da|calcula(?:me)?|cual es el resultado de|resultado de|cuanto son)\\b"), " ")
        // Separadores decimales: "3,5" → 3.5 ; miles con punto "1.000.000" → 1000000
        s = s.replace(Regex("(?<=\\d)\\.(?=\\d{3}(?!\\d))"), "")
        s = s.replace(Regex("(?<=\\d),(?=\\d)"), ".")
        s = s.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|por ciento)\\s*de\\s*"), "($1/100)*")
        s = s.replace(Regex("raiz cuadrada de"), " sqrt ")
        s = s.replace(Regex("raiz de"), " sqrt ")
        s = s.replace(Regex("(\\d+(?:\\.\\d+)?|\\))\\s*al cuadrado"), "$1^2")
        s = s.replace(Regex("(\\d+(?:\\.\\d+)?|\\))\\s*al cubo"), "$1^3")
        s = s.replace(Regex("elevado a(?: la)?"), "^")
        s = s.replace(Regex("\\b(dividido entre|dividido por|entre)\\b"), "/")
        s = s.replace(Regex("\\b(multiplicado por|por|x|×)\\b"), "*").replace("×", "*").replace("÷", "/")
        s = s.replace(Regex("\\bmas\\b"), "+").replace(Regex("\\bmenos\\b"), "-")
        s = s.replace(Regex("\\b(seno de|seno)\\b"), " sin ").replace(Regex("\\b(coseno de|coseno)\\b"), " cos ")
        s = s.replace(Regex("\\b(tangente de|tangente)\\b"), " tan ").replace(Regex("\\b(logaritmo natural de)\\b"), " ln ")
        s = s.replace(Regex("\\b(logaritmo de)\\b"), " log ").replace(Regex("\\bvalor absoluto de\\b"), " abs ")
        s = s.replace(Regex("\\bpi\\b"), " pi ")
        s = s.replace(Regex("[^0-9a-z.+\\-*/^%() ]"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun rest() = s.substring(i).take(20)
        fun skipSpaces() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skipSpaces(); return s.getOrNull(i) }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= parseFactor() }
                    '/' -> {
                        i++
                        val d = parseFactor()
                        if (d == 0.0) throw CalculationException("No se puede dividir entre cero.")
                        v /= d
                    }
                    '%' -> { i++; val d = parseFactor(); if (d == 0.0) throw CalculationException("Módulo entre cero."); v %= d }
                    else -> return v
                }
            }
        }

        fun parseFactor(): Double {
            val base = parseUnary()
            if (peek() == '^') {
                i++
                return base.pow(parseFactor())
            }
            return base
        }

        fun parseUnary(): Double = when (peek()) {
            '-' -> { i++; -parseUnary() }
            '+' -> { i++; parseUnary() }
            else -> parsePrimary()
        }

        fun parsePrimary(): Double {
            val c = peek() ?: throw CalculationException("La expresión está incompleta.")
            if (c == '(') {
                i++
                val v = parseExpression()
                if (peek() != ')') throw CalculationException("Falta cerrar un paréntesis.")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return s.substring(start, i).toDoubleOrNull() ?: throw CalculationException("Número inválido: ${s.substring(start, i)}")
            }
            if (c.isLetter()) {
                val start = i
                while (i < s.length && s[i].isLetter()) i++
                val name = s.substring(start, i)
                if (name == "pi") return Math.PI
                if (name == "e") return Math.E
                val arg = parseUnary()
                return when (name) {
                    "sqrt" -> if (arg < 0) throw CalculationException("No hay raíz real de un número negativo.") else sqrt(arg)
                    "sin" -> sin(Math.toRadians(arg))
                    "cos" -> cos(Math.toRadians(arg))
                    "tan" -> tan(Math.toRadians(arg))
                    "log" -> if (arg <= 0) throw CalculationException("Logaritmo de un número no positivo.") else log10(arg)
                    "ln" -> if (arg <= 0) throw CalculationException("Logaritmo de un número no positivo.") else ln(arg)
                    "abs" -> abs(arg)
                    "round" -> round(arg)
                    else -> throw CalculationException("Función desconocida: $name")
                }
            }
            throw CalculationException("Símbolo inesperado: $c")
        }
    }
}
