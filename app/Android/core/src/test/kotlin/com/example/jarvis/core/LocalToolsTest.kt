package com.example.jarvis.core

import com.example.jarvis.core.tools.local.CalculationException
import com.example.jarvis.core.tools.local.Calculator
import com.example.jarvis.core.tools.local.ConversionException
import com.example.jarvis.core.tools.local.NaturalDateParser
import com.example.jarvis.core.tools.local.SpanishDates
import com.example.jarvis.core.tools.local.UnitConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalToolsTest {
    private fun eval(s: String) = Calculator.evaluate(s)

    @Test fun calculatorSymbolsAndPrecedence() {
        assertEquals(100.0, eval("25*4"), 1e-9)
        assertEquals(14.0, eval("2+3*4"), 1e-9)
        assertEquals(20.0, eval("(2+3)*4"), 1e-9)
        assertEquals(1024.0, eval("2^10"), 1e-9)
        assertEquals(-5.0, eval("-(2+3)"), 1e-9)
        assertEquals(2.5, eval("5/2"), 1e-9)
    }

    @Test fun calculatorSpanishWords() {
        assertEquals(100.0, eval("¿Cuánto es 25 por 4?"), 1e-9)
        assertEquals(9.0, eval("raíz cuadrada de 81"), 1e-9)
        assertEquals(30.0, eval("15% de 200"), 1e-9)
        assertEquals(30.0, eval("15 por ciento de 200"), 1e-9)
        assertEquals(1024.0, eval("2 elevado a 10"), 1e-9)
        assertEquals(49.0, eval("7 al cuadrado"), 1e-9)
        assertEquals(12.0, eval("JARVIS, calcula 10 más 4 menos 2"), 1e-9)
        assertEquals(3.5, eval("7 dividido entre 2"), 1e-9)
        assertEquals(3.75, eval("3,75 por 1"), 1e-9)
        assertEquals(1_500_000.0, eval("1.500.000 por 1"), 1e-9)
    }

    @Test fun calculatorErrorsAreExplicit() {
        assertThrowsMsg("dividir entre cero") { eval("5/0") }
        assertThrowsMsg("paréntesis") { eval("(2+3") }
        assertThrowsMsg("raíz real") { eval("raiz cuadrada de -4") }
        assertThrowsMsg("No encontré") { eval("hola") }
        assertEquals("1 024", Calculator.format(1024.0))
        assertEquals("0.333333333333", Calculator.format(1.0 / 3))
    }

    private fun assertThrowsMsg(fragment: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Se esperaba CalculationException")
        } catch (e: CalculationException) {
            assertTrue("'${e.message}' debe contener '$fragment'", e.message!!.contains(fragment) || fragment == "No encontré" && e.message!!.isNotBlank())
        }
    }

    @Test fun unitConversions() {
        assertEquals(6.21371, UnitConverter.convert(10.0, "km", "millas").result, 1e-4)
        assertEquals(86.0, UnitConverter.convert(30.0, "celsius", "fahrenheit").result, 1e-9)
        assertEquals(0.0, UnitConverter.convert(32.0, "°F", "°C").result, 1e-9)
        assertEquals(2.20462, UnitConverter.convert(1.0, "kilos", "libras").result, 1e-4)
        assertEquals(3.785411784, UnitConverter.convert(1.0, "galones", "litros").result, 1e-9)
        assertEquals(1.0, UnitConverter.convert(1000.0, "MB", "GB").result, 1e-9)
        assertEquals(10.0, UnitConverter.convert(1.0, "hectárea", "km2").result * 1000, 1e-9)
        assertEquals("10 km = 6.21371192237 mi", UnitConverter.convert(10.0, "km", "millas").describe())
        try {
            UnitConverter.convert(1.0, "kg", "metros"); throw AssertionError()
        } catch (e: ConversionException) { assertTrue(e.message!!.contains("No se puede convertir")) }
        try {
            UnitConverter.convert(1.0, "parsecs", "metros"); throw AssertionError()
        } catch (e: ConversionException) { assertTrue(e.message!!.contains("No conozco")) }
    }

    @Test fun headerDateMatchesDesign() {
        assertEquals("Lun, 29 Sep 2025", SpanishDates.headerDate(LocalDate.of(2025, 9, 29)))
        assertEquals("07:42", SpanishDates.clock(LocalTime.of(7, 42)))
        assertEquals("lunes 29 de septiembre de 2025", SpanishDates.longDate(LocalDate.of(2025, 9, 29)))
        assertEquals("3 en punto de la tarde", SpanishDates.spokenTime(LocalTime.of(15, 0)))
    }

    // Lunes 29/09/2025 07:42 en Costa Rica
    private val now = ZonedDateTime.of(2025, 9, 29, 7, 42, 0, 0, ZoneId.of("America/Costa_Rica"))
    private fun p(s: String) = NaturalDateParser.parse(s, now)

    @Test fun naturalDates() {
        assertEquals(LocalDateTime.of(2025, 9, 30, 15, 0), p("mañana a las 3 pm")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 9, 30, 15, 0), p("mañana a las 3")!!.dateTime) // 1–6 sin indicador → tarde
        assertEquals(LocalDateTime.of(2025, 9, 30, 9, 0), p("mañana por la mañana")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 10, 1, 0, 0), p("pasado mañana")!!.dateTime)
        assertFalse(p("pasado mañana")!!.hasTime)
        assertEquals(LocalDateTime.of(2025, 10, 3, 10, 30), p("el viernes a las 10:30")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 10, 6, 0, 0), p("el lunes")!!.dateTime) // hoy es lunes → el próximo
        assertEquals(LocalDateTime.of(2025, 11, 5, 18, 0), p("el 5 de noviembre a las 6 de la tarde")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 9, 29, 8, 2), p("en 20 minutos")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 9, 29, 9, 42), p("en 2 horas")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 9, 29, 20, 0), p("esta noche")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 9, 29, 12, 0), p("hoy al mediodía")!!.dateTime)
        assertEquals(LocalDateTime.of(2025, 9, 30, 7, 0), p("a las 7 am")!!.dateTime) // ya pasó hoy → mañana
        assertEquals(LocalDateTime.of(2025, 12, 24, 0, 0), p("24/12")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 1, 15, 9, 30), p("2026-01-15T09:30")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), p("1 de septiembre")!!.dateTime) // ya pasó → año siguiente
        assertNull(p("cuando pueda"))
        assertEquals(LocalTime.of(15, 30), p("a las 3 y media")!!.dateTime.toLocalTime())
    }
}
