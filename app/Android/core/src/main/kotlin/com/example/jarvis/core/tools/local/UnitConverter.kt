package com.example.jarvis.core.tools.local

import com.example.jarvis.core.text.TextNormalizer

class ConversionException(message: String) : Exception(message)

/** Conversión de unidades con nombres en español y abreviaturas. Factores respecto a la unidad base de cada magnitud. */
object UnitConverter {
    private data class UnitDef(val dimension: String, val factor: Double, val display: String)

    private val units: Map<String, UnitDef> = buildMap {
        fun add(dim: String, factor: Double, display: String, vararg names: String) {
            for (n in names) put(TextNormalizer.fold(n), UnitDef(dim, factor, display))
        }
        // Longitud (base: metro)
        add("longitud", 1000.0, "km", "km", "kilometro", "kilometros")
        add("longitud", 1.0, "m", "m", "metro", "metros")
        add("longitud", 0.01, "cm", "cm", "centimetro", "centimetros")
        add("longitud", 0.001, "mm", "mm", "milimetro", "milimetros")
        add("longitud", 1609.344, "mi", "mi", "milla", "millas")
        add("longitud", 0.9144, "yd", "yd", "yarda", "yardas")
        add("longitud", 0.3048, "ft", "ft", "pie", "pies")
        add("longitud", 0.0254, "in", "in", "pulgada", "pulgadas")
        add("longitud", 1852.0, "nmi", "milla nautica", "millas nauticas", "nmi")
        // Masa (base: kilogramo)
        add("masa", 1000.0, "t", "t", "tonelada", "toneladas")
        add("masa", 1.0, "kg", "kg", "kilo", "kilos", "kilogramo", "kilogramos")
        add("masa", 0.001, "g", "g", "gramo", "gramos")
        add("masa", 0.000001, "mg", "mg", "miligramo", "miligramos")
        add("masa", 0.45359237, "lb", "lb", "lbs", "libra", "libras")
        add("masa", 0.028349523125, "oz", "oz", "onza", "onzas")
        // Volumen (base: litro)
        add("volumen", 1.0, "L", "l", "litro", "litros")
        add("volumen", 0.001, "mL", "ml", "mililitro", "mililitros")
        add("volumen", 3.785411784, "gal (EE. UU.)", "gal", "galon", "galones")
        add("volumen", 1000.0, "m³", "m3", "metro cubico", "metros cubicos")
        add("volumen", 0.0295735295625, "fl oz", "onza liquida", "onzas liquidas", "fl oz")
        // Velocidad (base: m/s)
        add("velocidad", 1.0, "m/s", "m/s", "metros por segundo")
        add("velocidad", 1 / 3.6, "km/h", "km/h", "kmh", "kilometros por hora")
        add("velocidad", 0.44704, "mph", "mph", "millas por hora")
        add("velocidad", 0.514444, "nudos", "nudo", "nudos", "kn")
        // Área (base: m²)
        add("area", 1.0, "m²", "m2", "metro cuadrado", "metros cuadrados")
        add("area", 1_000_000.0, "km²", "km2", "kilometro cuadrado", "kilometros cuadrados")
        add("area", 10_000.0, "ha", "ha", "hectarea", "hectareas")
        add("area", 4046.8564224, "acres", "acre", "acres")
        add("area", 0.09290304, "ft²", "ft2", "pie cuadrado", "pies cuadrados")
        // Datos (base: byte, prefijos decimales SI)
        add("datos", 1.0, "B", "b", "byte", "bytes")
        add("datos", 1e3, "KB", "kb", "kilobyte", "kilobytes")
        add("datos", 1e6, "MB", "mb", "megabyte", "megabytes")
        add("datos", 1e9, "GB", "gb", "gigabyte", "gigabytes", "gigas")
        add("datos", 1e12, "TB", "tb", "terabyte", "terabytes", "teras")
        // Tiempo (base: segundo)
        add("tiempo", 1.0, "s", "s", "seg", "segundo", "segundos")
        add("tiempo", 60.0, "min", "min", "minuto", "minutos")
        add("tiempo", 3600.0, "h", "h", "hora", "horas")
        add("tiempo", 86400.0, "días", "dia", "dias")
        add("tiempo", 604800.0, "semanas", "semana", "semanas")
    }

    private val temperature = mapOf(
        "c" to "°C", "°c" to "°C", "celsius" to "°C", "centigrados" to "°C", "grados celsius" to "°C", "grados centigrados" to "°C",
        "f" to "°F", "°f" to "°F", "fahrenheit" to "°F", "grados fahrenheit" to "°F",
        "k" to "K", "kelvin" to "K", "grados kelvin" to "K",
    )

    data class Conversion(val value: Double, val from: String, val to: String, val result: Double, val dimension: String) {
        fun describe(): String = "${Calculator.format(value)} $from = ${Calculator.format(result)} $to"
    }

    fun convert(value: Double, fromRaw: String, toRaw: String): Conversion {
        val from = TextNormalizer.fold(fromRaw).trim()
        val to = TextNormalizer.fold(toRaw).trim()
        val tFrom = temperature[from]
        val tTo = temperature[to]
        if (tFrom != null || tTo != null) {
            if (tFrom == null || tTo == null) throw ConversionException("No puedo convertir entre temperatura y otra magnitud.")
            val celsius = when (tFrom) { "°F" -> (value - 32) * 5 / 9; "K" -> value - 273.15; else -> value }
            val result = when (tTo) { "°F" -> celsius * 9 / 5 + 32; "K" -> celsius + 273.15; else -> celsius }
            return Conversion(value, tFrom, tTo, result, "temperatura")
        }
        val a = units[from] ?: throw ConversionException("No conozco la unidad \"$fromRaw\".")
        val b = units[to] ?: throw ConversionException("No conozco la unidad \"$toRaw\".")
        if (a.dimension != b.dimension) throw ConversionException("No se puede convertir ${a.dimension} en ${b.dimension}.")
        return Conversion(value, a.display, b.display, value * a.factor / b.factor, a.dimension)
    }

    fun knows(unit: String): Boolean = TextNormalizer.fold(unit).trim().let { it in units || it in temperature }
}
