package com.example.jarvis.ui.core

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.jarvis.core.business.LatLon
import com.example.jarvis.core.globe.Cities
import com.example.jarvis.core.globe.GlobeModel
import com.example.jarvis.core.globe.LandMask
import com.example.jarvis.core.model.CoreState
import com.example.jarvis.ui.theme.HudPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Parámetros visuales por estado real del asistente (sin animar actividad inexistente). */
private data class StateLook(val spin: Float, val intensity: Float, val scan: Float, val exec: Float, val error: Float)

private fun lookFor(s: CoreState) = when (s) {
    CoreState.IDLE -> StateLook(spin = 0.07f, intensity = 0.8f, scan = 0f, exec = 0f, error = 0f)
    CoreState.LISTENING -> StateLook(0.09f, 1.0f, 0f, 0f, 0f)
    CoreState.THINKING -> StateLook(0.28f, 1.0f, 1f, 0f, 0f)
    CoreState.EXECUTING -> StateLook(0.38f, 1.1f, 0.4f, 1f, 0f)
    CoreState.SPEAKING -> StateLook(0.12f, 1.1f, 0f, 0f, 0f)
    CoreState.ERROR -> StateLook(0.02f, 0.75f, 0f, 0f, 1f)
}

/** Búferes reutilizados entre fotogramas: cero asignaciones durante el dibujo. */
private class GlobeBuffers(val model: GlobeModel) {
    val proj = FloatArray(model.landCount * 2)
    val depth = FloatArray(model.landCount)
    val buckets = Array(BUCKETS) { FloatArray(model.landCount * 2) }
    val bucketCount = IntArray(BUCKETS)
    val hot = FloatArray(model.landCount * 2)
    var hotCount = 0
    val cityProj = FloatArray(model.cities.size * 2)
    val cityDepth = FloatArray(model.cities.size)
    val grid: FloatArray
    val gridCount: Int
    val gridProj: FloatArray
    val gridDepth: FloatArray
    val arcs: List<FloatArray>
    val arcProj = FloatArray((ARC_STEPS + 1) * 2)
    val arcDepth = FloatArray(ARC_STEPS + 1)
    val one = FloatArray(3)
    val oneProj = FloatArray(2)
    val oneDepth = FloatArray(1)
    val paints = Array(BUCKETS + 1) { Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND } }
    val path = Path()
    val pathBack = Path()

    init {
        // Retícula: paralelos cada 30° y meridianos cada 30°, como polilíneas de puntos unitarios
        val pts = ArrayList<Float>()
        for (lat in -60..60 step 30) for (lon in -180..180 step 4) { GlobeModel.toUnit(lat.toDouble(), lon.toDouble(), one, 0); pts += one[0]; pts += one[1]; pts += one[2] }
        for (lon in -180 until 180 step 30) for (lat in -84..84 step 4) { GlobeModel.toUnit(lat.toDouble(), lon.toDouble(), one, 0); pts += one[0]; pts += one[1]; pts += one[2] }
        grid = pts.toFloatArray()
        gridCount = grid.size / 3
        gridProj = FloatArray(gridCount * 2)
        gridDepth = FloatArray(gridCount)
        // Arcos de red decorativos entre capitales reales (selección fija)
        val names = listOf("San José" to "Panama City", "San José" to "Mexico City", "San José" to "Bogota", "Bogota" to "Caracas",
            "Mexico City" to "Washington", "Lima" to "Santiago", "Lima" to "Buenos Aires", "Washington" to "Ottawa",
            "Caracas" to "San José", "Lima" to "Bogota")
        fun indexOf(prefix: String) = model.cities.indexOfFirst { it.name.startsWith(prefix) }.takeIf { it >= 0 }
        arcs = names.mapNotNull { (a, b) ->
            val ia = indexOf(a) ?: return@mapNotNull null
            val ib = indexOf(b) ?: return@mapNotNull null
            FloatArray((ARC_STEPS + 1) * 3).also { GlobeModel.greatArc(model.cityVectors, ia * 3, model.cityVectors, ib * 3, ARC_STEPS, 0.10f, it) }
        }
    }

    companion object {
        const val BUCKETS = 4
        const val ARC_STEPS = 32
    }
}

/**
 * Núcleo holográfico: globo terráqueo con datos reales de Natural Earth (dominio público),
 * anillos orbitales, arcos HUD y pedestal. Su animación refleja el estado real del asistente.
 */
@Composable
fun HoloGlobe(
    state: CoreState,
    micLevel: Float,
    outputLevel: Float,
    palette: HudPalette,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    highlight: LatLon? = null,
) {
    val buffers by produceState<GlobeBuffers?>(null) {
        value = withContext(Dispatchers.Default) { GlobeBuffers(GlobeModel(LandMask.load(), samples = 22000, cities = Cities.load())) }
    }
    val look = lookFor(state)
    val spin by animateFloatAsState(look.spin, tween(900), label = "spin")
    val intensity by animateFloatAsState(look.intensity, tween(600), label = "intensity")
    val scan by animateFloatAsState(look.scan, tween(500), label = "scan")
    val exec by animateFloatAsState(look.exec, tween(400), label = "exec")
    val error by animateFloatAsState(look.error, tween(400), label = "error")
    val mic by animateFloatAsState(if (state == CoreState.LISTENING) micLevel else 0f, tween(120), label = "mic")
    val out by animateFloatAsState(if (state == CoreState.SPEAKING) outputLevel else 0f, tween(120), label = "out")

    // Tiempo y rotación integrados por fotograma (la rotación no salta al cambiar de estado)
    val clock = remember { mutableFloatStateOf(0f) }
    val yawState = remember { mutableFloatStateOf(GlobeModel.yawFacing(-80.0)) }
    val spinNow by rememberUpdatedState(spin)
    val motion = if (reduceMotion) 0.25f else 1f
    LaunchedEffect(reduceMotion) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                clock.floatValue += dt * motion
                yawState.floatValue += dt * spinNow * motion
            }
        }
    }

    Canvas(modifier.semantics { contentDescription = "Núcleo de JARVIS: ${stateLabel(state)}" }) {
        val b = buffers ?: return@Canvas
        val t = clock.floatValue
        drawGlobe(b, t, yawState.floatValue, palette, intensity, scan, exec, error, mic, out, highlight, reduceMotion)
    }
}

fun stateLabel(s: CoreState) = when (s) {
    CoreState.IDLE -> "en espera"
    CoreState.LISTENING -> "escuchando"
    CoreState.THINKING -> "procesando"
    CoreState.EXECUTING -> "ejecutando herramienta"
    CoreState.SPEAKING -> "hablando"
    CoreState.ERROR -> "error"
}

private fun DrawScope.drawGlobe(
    b: GlobeBuffers, t: Float, yaw: Float, p: HudPalette, intensity: Float, scan: Float, exec: Float, error: Float,
    mic: Float, out: Float, highlight: LatLon?, reduceMotion: Boolean,
) {
    val w = size.width
    val h = size.height
    val r = min(w * 0.40f, h * 0.36f) * (1f + out * 0.025f)
    val cx = w / 2f
    val cy = h * 0.43f
    val tilt = 0.34f
    val core = lerp(p.primary, p.error, error)
    val hot = lerp(p.gold, p.error, error)
    val deep = lerp(p.deep, p.error.copy(alpha = 0.7f), error)
    val breath = 0.9f + 0.1f * sin(t * 1.4f)
    val glowA = intensity * breath

    // 1. Halo de fondo
    drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.30f * glowA), core.copy(alpha = 0.08f * glowA), Color.Transparent), Offset(cx, cy), r * 2.2f), r * 2.2f, Offset(cx, cy))

    // 2. Arcos HUD exteriores (rotan; en EXECUTING más rápidos y brillantes)
    for (i in 0 until 3) {
        val rr = r * (1.20f + i * 0.10f)
        val speed = (if (i % 2 == 0) 1 else -1) * (10f + i * 6f) * (1f + exec * 3f)
        val start = t * speed
        val segs = 3 + i
        for (s in 0 until segs) {
            val sweep = 360f / segs * 0.55f
            drawArc(core.copy(alpha = (0.35f + 0.25f * exec) * glowA), start + s * 360f / segs, sweep, false,
                topLeft = Offset(cx - rr, cy - rr), size = Size(rr * 2, rr * 2), style = Stroke(width = (1.2f + (2 - i) * 0.6f) * density, cap = StrokeCap.Round))
        }
    }
    // Marcas de graduación
    val tickR = r * 1.52f
    for (k in 0 until 72) {
        val a = (k * 5f + t * 2f) * PI.toFloat() / 180f
        val len = if (k % 6 == 0) 9f * density else 4f * density
        val ca = cos(a); val sa = sin(a)
        drawLine(core.copy(alpha = if (k % 6 == 0) 0.55f * glowA else 0.25f * glowA), Offset(cx + ca * tickR, cy + sa * tickR), Offset(cx + ca * (tickR + len), cy + sa * (tickR + len)), strokeWidth = density)
    }

    // 3. Anillos orbitales: mitad trasera
    val rings = 3
    for (k in 0 until rings) drawRing(b, cx, cy, r, k, t, core, back = true, glowA)

    // 4. Cuerpo del globo
    drawCircle(Brush.radialGradient(listOf(deep.copy(alpha = 0.40f), Color(0xF00C0400), Color(0xFA050200)), Offset(cx - r * 0.25f, cy - r * 0.3f), r * 1.25f), r, Offset(cx, cy))

    // 5. Retícula
    val gn = b.model.project(b.grid, b.gridCount, yaw, tilt, b.gridProj, b.gridDepth, frontOnly = false)
    val gridPaint = b.paints[b.paints.size - 1]
    gridPaint.color = core.copy(alpha = 0.22f * glowA).toArgb()
    gridPaint.strokeWidth = 1.1f * density
    drawIntoCanvas { c ->
        // Dibujamos solo puntos de la cara visible, como trazo punteado
        var n = 0
        for (i in 0 until gn) if (b.gridDepth[i] > 0.05f) { b.hot[n * 2] = cx + b.gridProj[i * 2] * r; b.hot[n * 2 + 1] = cy - b.gridProj[i * 2 + 1] * r; n++ }
        c.nativeCanvas.drawPoints(b.hot, 0, n * 2, gridPaint)
    }

    // 6. Continentes: puntos por profundidad (brillo y tamaño mayores al frente) + banda de escaneo
    val n = b.model.project(b.model.land, b.model.landCount, yaw, tilt, b.proj, b.depth)
    b.bucketCount.fill(0)
    b.hotCount = 0
    val scanY = sin(t * 1.7f) * 0.95f
    for (i in 0 until n) {
        val x = cx + b.proj[i * 2] * r
        val y = cy - b.proj[i * 2 + 1] * r
        if (scan > 0.01f && kotlin.math.abs(b.proj[i * 2 + 1] - scanY) < 0.035f) {
            b.hot[b.hotCount * 2] = x; b.hot[b.hotCount * 2 + 1] = y; b.hotCount++
            continue
        }
        val k = (b.depth[i] * GlobeBuffers.BUCKETS).toInt().coerceIn(0, GlobeBuffers.BUCKETS - 1)
        val arr = b.buckets[k]
        val c = b.bucketCount[k]
        arr[c * 2] = x; arr[c * 2 + 1] = y
        b.bucketCount[k] = c + 1
    }
    drawIntoCanvas { c ->
        for (k in 0 until GlobeBuffers.BUCKETS) {
            val f = (k + 1f) / GlobeBuffers.BUCKETS
            val paint = b.paints[k]
            paint.color = lerp(deep, if (k == GlobeBuffers.BUCKETS - 1) hot else core, f).copy(alpha = (0.35f + 0.6f * f) * intensity).toArgb()
            paint.strokeWidth = (1.3f + 1.4f * f) * density * (r / (160f * density)).coerceIn(0.7f, 1.4f)
            c.nativeCanvas.drawPoints(b.buckets[k], 0, b.bucketCount[k] * 2, paint)
        }
        if (b.hotCount > 0) {
            val paint = b.paints[GlobeBuffers.BUCKETS]
            paint.color = hot.copy(alpha = scan).toArgb()
            paint.strokeWidth = 3.2f * density
            c.nativeCanvas.drawPoints(b.hot, 0, b.hotCount * 2, paint)
        }
    }

    // 7. Arcos de red con "paquete" viajando
    if (!reduceMotion) {
        b.arcs.forEachIndexed { idx, arc ->
            val m = b.model.project(arc, GlobeBuffers.ARC_STEPS + 1, yaw, tilt, b.arcProj, b.arcDepth, frontOnly = false)
            b.path.reset()
            var started = false
            for (i in 0 until m) {
                val x = cx + b.arcProj[i * 2] * r; val y = cy - b.arcProj[i * 2 + 1] * r
                if (b.arcDepth[i] > 0f) { if (!started) { b.path.moveTo(x, y); started = true } else b.path.lineTo(x, y) } else started = false
            }
            drawPath(b.path, hot.copy(alpha = 0.45f * intensity), style = Stroke(width = 1.1f * density))
            val f = ((t * 0.25f + idx * 0.17f) % 1f)
            val pi = (f * GlobeBuffers.ARC_STEPS).toInt().coerceIn(0, GlobeBuffers.ARC_STEPS)
            if (b.arcDepth[pi] > 0f) drawCircle(hot.copy(alpha = 0.9f * intensity), 2.2f * density, Offset(cx + b.arcProj[pi * 2] * r, cy - b.arcProj[pi * 2 + 1] * r))
        }
    }

    // 8. Capitales reales (luces)
    val cn = b.model.project(b.model.cityVectors, b.model.cities.size, yaw, tilt, b.cityProj, b.cityDepth)
    for (i in 0 until cn) {
        val x = cx + b.cityProj[i * 2] * r; val y = cy - b.cityProj[i * 2 + 1] * r
        val tw = 0.75f + 0.25f * sin(t * 2.3f + i)
        drawCircle(hot.copy(alpha = 0.18f * tw * intensity), 5f * density, Offset(x, y))
        drawCircle(Color(0xFFFFF1C8).copy(alpha = 0.9f * tw * b.cityDepth[i]), 1.5f * density, Offset(x, y))
    }

    // 9. Ubicación del usuario (si la configuró): anillo pulsante
    if (highlight != null) {
        GlobeModel.toUnit(highlight.lat, highlight.lon, b.one, 0)
        if (b.model.project(b.one, 1, yaw, tilt, b.oneProj, b.oneDepth) == 1) {
            val x = cx + b.oneProj[0] * r; val y = cy - b.oneProj[1] * r
            val ph = (t * 0.8f) % 1f
            drawCircle(p.bright.copy(alpha = (1f - ph) * 0.8f), (4f + ph * 16f) * density, Offset(x, y), style = Stroke(1.5f * density))
            drawCircle(p.bright, 2.5f * density, Offset(x, y))
        }
    }

    // 10. Borde luminoso del globo
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, core.copy(alpha = 0.0f), core.copy(alpha = 0.45f * glowA)), Offset(cx, cy), r * 1.02f), r * 1.02f, Offset(cx, cy))
    drawCircle(core.copy(alpha = 0.85f * glowA), r, Offset(cx, cy), style = Stroke(1.6f * density))
    drawCircle(core.copy(alpha = 0.15f * glowA), r + 4f * density, Offset(cx, cy), style = Stroke(6f * density))

    // 11. Anillos orbitales: mitad delantera + satélites
    for (k in 0 until rings) drawRing(b, cx, cy, r, k, t, core, back = false, glowA)

    // 12. Ondas: escucha (micrófono) y voz (salida)
    if (mic > 0.02f || out > 0.02f) {
        val level = maxOf(mic, out)
        for (i in 0 until 3) {
            val ph = ((t * 0.9f + i / 3f) % 1f)
            drawCircle((if (out > mic) hot else p.bright).copy(alpha = (1f - ph) * (0.25f + level * 0.6f)), r * (1.04f + ph * (0.25f + level * 0.35f)), Offset(cx, cy), style = Stroke((1.2f + level * 2f) * density))
        }
    }

    // 13. Pedestal holográfico y haz de luz
    val py = cy + r * 1.36f
    drawPath(Path().apply {
        moveTo(cx - r * 0.30f, py); lineTo(cx + r * 0.30f, py); lineTo(cx + r * 0.55f, cy + r * 0.85f); lineTo(cx - r * 0.55f, cy + r * 0.85f); close()
    }, Brush.verticalGradient(listOf(core.copy(alpha = 0f), core.copy(alpha = 0.16f * glowA)), startY = cy + r * 0.85f, endY = py))
    val ringsP = floatArrayOf(1.45f, 1.18f, 0.92f, 0.66f, 0.40f)
    ringsP.forEachIndexed { i, k ->
        val rx = r * k; val ry = rx * 0.17f
        val wave = if (out > 0.02f) out * 0.05f * sin(t * 9f + i) else 0f
        drawOval(core.copy(alpha = (0.25f + 0.12f * i) * glowA), Offset(cx - rx * (1 + wave), py - ry), Size(rx * 2 * (1 + wave), ry * 2), style = Stroke((1f + i * 0.4f) * density))
    }
    drawOval(Brush.radialGradient(listOf(hot.copy(alpha = 0.55f * glowA), core.copy(alpha = 0.10f), Color.Transparent), Offset(cx, py), r * 0.5f), Offset(cx - r * 0.5f, py - r * 0.085f), Size(r, r * 0.17f))
    // Segmentos rotatorios del aro exterior del pedestal
    val outerRx = r * 1.6f; val outerRy = outerRx * 0.17f
    for (s in 0 until 8) {
        val start = t * 25f + s * 45f
        drawArc(hot.copy(alpha = 0.55f * glowA), start, 22f, false, Offset(cx - outerRx, py - outerRy), Size(outerRx * 2, outerRy * 2), style = Stroke(2.2f * density, cap = StrokeCap.Round))
    }
}

/** Anillo orbital inclinado, dividido en mitad trasera (tras el globo) y delantera. */
private fun DrawScope.drawRing(b: GlobeBuffers, cx: Float, cy: Float, r: Float, k: Int, t: Float, color: Color, back: Boolean, glowA: Float) {
    val radius = r * (1.18f + k * 0.14f)
    val incl = 1.32f + k * 0.06f
    val roll = -0.18f + k * 0.16f + 0.02f * sin(t * 0.3f + k)
    val ci = cos(incl); val si = sin(incl); val cr = cos(roll); val sr = sin(roll)
    val path = b.path
    path.reset()
    var started = false
    val steps = 96
    for (s in 0..steps) {
        val a = s * 2f * PI.toFloat() / steps
        val x = cos(a) * radius
        val z0 = sin(a) * radius
        val y1 = -z0 * ci
        val z1 = z0 * si
        val sx = cx + x * cr - y1 * sr
        val sy = cy + x * sr + y1 * cr
        val isBack = z1 < 0f
        if (isBack == back) { if (!started) { path.moveTo(sx, sy); started = true } else path.lineTo(sx, sy) } else started = false
    }
    val alpha = if (back) 0.22f else 0.75f
    drawPath(path, color.copy(alpha = alpha * glowA), style = Stroke(width = (if (k == 0) 1.8f else 1.1f) * density))
    // Satélite que recorre el anillo
    val a = t * (0.5f + k * 0.2f) + k * 2f
    val z0 = sin(a) * radius
    val z1 = z0 * si
    if ((z1 < 0f) == back) {
        val x = cos(a) * radius; val y1 = -z0 * ci
        drawCircle(color.copy(alpha = if (back) 0.4f else 1f), (if (back) 1.8f else 3f) * density, Offset(cx + x * cr - y1 * sr, cy + x * sr + y1 * cr))
    }
}
