package com.example.jarvis.core

import com.example.jarvis.core.device.AppMatcher
import com.example.jarvis.core.device.InstalledApp
import com.example.jarvis.core.device.SpecialApp
import com.example.jarvis.core.globe.Cities
import com.example.jarvis.core.globe.GlobeModel
import com.example.jarvis.core.globe.LandMask
import com.example.jarvis.core.model.CoreState
import com.example.jarvis.core.orchestrator.CoreStateArbiter
import com.example.jarvis.core.realtime.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GlobeTest {
    private val mask = LandMask.load()

    @Test fun landMaskKnownPoints() {
        assertTrue("San José, Costa Rica", mask.isLand(9.93, -84.08))
        assertTrue("París", mask.isLand(48.85, 2.35))
        assertTrue("Brasilia", mask.isLand(-15.8, -47.9))
        assertFalse("Pacífico", mask.isLand(0.0, -140.0))
        assertFalse("Atlántico", mask.isLand(30.0, -40.0))
    }

    @Test fun globeModelSamplesRealLandFraction() {
        val g = GlobeModel(mask, samples = 20000)
        val fraction = g.landCount / 20000.0
        assertTrue("fracción de tierra ≈ 0,29–0,36 (incluye Antártida), fue $fraction", fraction in 0.27..0.38)
        val cities = Cities.load()
        assertTrue(cities.any { it.name == "San José" && abs(it.lat - 9.93) < 0.01 })
    }

    @Test fun projectionCentersFacedLongitude() {
        val g = GlobeModel(mask, samples = 2000)
        val v = FloatArray(3)
        GlobeModel.toUnit(9.93, -84.08, v, 0)
        val out = FloatArray(2)
        val depth = FloatArray(1)
        val n = g.project(v, 1, GlobeModel.yawFacing(-84.08), 0f, out, depth)
        assertEquals(1, n)
        assertEquals(0f, out[0], 1e-4f)
        assertTrue(depth[0] > 0.98f)
        // Del otro lado del globo no se dibuja
        assertEquals(0, g.project(v, 1, GlobeModel.yawFacing(-84.08 + 180), 0f, out, depth))
    }

    @Test fun stateArbiterPriorities() {
        assertEquals(CoreState.IDLE, CoreStateArbiter.combine(CoreState.IDLE, VoiceState.WAKE_WORD))
        assertEquals(CoreState.LISTENING, CoreStateArbiter.combine(CoreState.IDLE, VoiceState.USER_SPEAKING))
        assertEquals(CoreState.SPEAKING, CoreStateArbiter.combine(CoreState.THINKING, VoiceState.SPEAKING))
        assertEquals(CoreState.EXECUTING, CoreStateArbiter.combine(CoreState.EXECUTING, VoiceState.SPEAKING))
        assertEquals(CoreState.ERROR, CoreStateArbiter.combine(CoreState.IDLE, VoiceState.ERROR))
        assertEquals(CoreState.THINKING, CoreStateArbiter.combine(CoreState.IDLE, VoiceState.CONNECTING))
    }

    @Test fun appMatcher() {
        val apps = listOf(InstalledApp("Spotify", "com.spotify.music"), InstalledApp("WhatsApp", "com.whatsapp"), InstalledApp("Google Maps", "com.google.android.apps.maps"))
        assertEquals("com.spotify.music", (AppMatcher.match("spotify", apps) as AppMatcher.Match.App).app.packageName)
        assertEquals("com.whatsapp", (AppMatcher.match("wasap", apps) as AppMatcher.Match.App).app.packageName)
        assertEquals("com.google.android.apps.maps", (AppMatcher.match("mapas", apps) as AppMatcher.Match.App).app.packageName)
        assertEquals(SpecialApp.CAMERA, (AppMatcher.match("la cámara", apps) as AppMatcher.Match.Special).kind)
        assertTrue(AppMatcher.match("Netflix", apps) is AppMatcher.Match.NotFound)
    }

    /**
     * Renderiza una vista previa del globo con la misma geometría que usa la app (Java2D en lugar
     * de Compose) para revisión visual: build/globe-preview.png.
     */
    @Test fun renderPreview() {
        val size = 640
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(2, 4, 8); g2.fillRect(0, 0, size, size)
        val cx = size / 2f; val cy = size / 2f; val r = size * 0.36f
        val model = GlobeModel(mask, samples = 26000, cities = Cities.load())
        val yaw = GlobeModel.yawFacing(-75.0); val tilt = -0.32f
        // brillo de fondo
        for (i in 12 downTo 1) { g2.color = Color(255, 110, 0, 4 + i); val rr = r * (1 + i * 0.04f); g2.fill(Ellipse2D.Float(cx - rr, cy - rr, rr * 2, rr * 2)) }
        g2.color = Color(40, 12, 0); g2.fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
        // retícula
        g2.stroke = BasicStroke(0.8f)
        val pts = FloatArray(2); val dep = FloatArray(1); val v = FloatArray(3)
        for (lat in -60..60 step 30) for (lon in -180 until 180 step 2) {
            GlobeModel.toUnit(lat.toDouble(), lon.toDouble(), v, 0)
            if (model.project(v, 1, yaw, tilt, pts, dep) == 1) { g2.color = Color(255, 120, 20, (40 * dep[0]).toInt() + 10); g2.fillRect((cx + pts[0] * r).toInt(), (cy - pts[1] * r).toInt(), 1, 1) }
        }
        // tierra
        val out = FloatArray(model.landCount * 2); val depth = FloatArray(model.landCount)
        val n = model.project(model.land, model.landCount, yaw, tilt, out, depth)
        for (i in 0 until n) {
            val a = (90 + 165 * depth[i]).toInt().coerceIn(0, 255)
            g2.color = Color(255, (110 + 60 * depth[i]).toInt(), 20, a)
            val s = 1.6f + depth[i]
            g2.fill(Ellipse2D.Float(cx + out[i * 2] * r - s / 2, cy - out[i * 2 + 1] * r - s / 2, s, s))
        }
        // ciudades reales
        val cOut = FloatArray(model.cities.size * 2); val cDep = FloatArray(model.cities.size)
        val cn = model.project(model.cityVectors, model.cities.size, yaw, tilt, cOut, cDep)
        for (i in 0 until cn) { g2.color = Color(255, 225, 150, 230); g2.fill(Ellipse2D.Float(cx + cOut[i * 2] * r - 2.5f, cy - cOut[i * 2 + 1] * r - 2.5f, 5f, 5f)) }
        // anillo orbital inclinado
        g2.stroke = BasicStroke(1.4f); g2.color = Color(255, 150, 40, 170)
        val ring = java.awt.geom.Path2D.Float()
        for (k in 0..360) { val t = Math.toRadians(k.toDouble()); val x = cx + cos(t).toFloat() * r * 1.35f; val y = cy + sin(t).toFloat() * r * 0.22f + cos(t).toFloat() * r * 0.08f; if (k == 0) ring.moveTo(x, y) else ring.lineTo(x, y) }
        g2.draw(ring)
        g2.dispose()
        val file = File("build/globe-preview.png")
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
        assertTrue(file.length() > 10_000)
    }
}
