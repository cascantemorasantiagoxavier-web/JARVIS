package com.example.jarvis.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reloj inyectable para que fechas, recordatorios y caducidades sean testeables. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
    fun zonedNow(): ZonedDateTime = now().atZone(zone())
}

object SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedTimeSource(var instant: Instant, private val zoneId: ZoneId = ZoneId.of("America/Costa_Rica")) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}
