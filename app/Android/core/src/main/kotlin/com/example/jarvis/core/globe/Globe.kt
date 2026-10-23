package com.example.jarvis.core.globe

import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Máscara de tierra (Natural Earth, dominio público) rasterizada a 0,5°.
 * Formato: "LM1 <cols> <rows>\n" + bitset fila a fila desde +90° y desde -180°.
 */
class LandMask(val cols: Int, val rows: Int, private val bits: ByteArray) {
    fun isLand(latDeg: Double, lonDeg: Double): Boolean {
        val r = ((90.0 - latDeg) / 180.0 * rows).toInt().coerceIn(0, rows - 1)
        val lon = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0
        val c = (lon / 360.0 * cols).toInt().coerceIn(0, cols - 1)
        val i = r * cols + c
        return (bits[i ushr 3].toInt() and (0x80 ushr (i and 7))) != 0
    }

    companion object {
        fun load(loader: ClassLoader = LandMask::class.java.classLoader): LandMask {
            val stream = loader.getResourceAsStream("landmask.bin.gz") ?: error("Falta landmask.bin.gz")
            DataInputStream(GZIPInputStream(stream)).use { input ->
                val header = StringBuilder()
                while (true) {
                    val ch = input.read()
                    require(ch >= 0) { "Máscara truncada" }
                    if (ch == '\n'.code) break
                    header.append(ch.toChar())
                }
                val parts = header.split(' ')
                require(parts.size == 3 && parts[0] == "LM1") { "Cabecera inválida: $header" }
                val cols = parts[1].toInt()
                val rows = parts[2].toInt()
                val bytes = ByteArray((cols * rows + 7) / 8)
                input.readFully(bytes)
                return LandMask(cols, rows, bytes)
            }
        }
    }
}

data class City(val lat: Double, val lon: Double, val name: String)

object Cities {
    fun load(loader: ClassLoader = Cities::class.java.classLoader): List<City> =
        loader.getResourceAsStream("capitals.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val p = line.split(';')
                if (p.size < 3) null else City(p[0].toDouble(), p[1].toDouble(), p[2])
            }.toList()
        }.orEmpty()
}

/**
 * Geometría del globo holográfico: puntos de tierra sobre una esfera de Fibonacci (densidad
 * uniforme) y proyección ortográfica con rotación. Todo en arrays primitivos reutilizables para
 * dibujar miles de puntos por fotograma sin crear objetos.
 */
class GlobeModel(mask: LandMask, samples: Int = 24000, val cities: List<City> = emptyList()) {
    /** Vectores unitarios (x, y, z) de los puntos de tierra. */
    val land: FloatArray
    val landCount: Int
    /** Vectores unitarios de las ciudades. */
    val cityVectors: FloatArray = FloatArray(cities.size * 3).also { arr ->
        cities.forEachIndexed { i, c -> toUnit(c.lat, c.lon, arr, i * 3) }
    }

    init {
        val tmp = FloatArray(samples * 3)
        var n = 0
        val golden = PI * (3.0 - sqrt(5.0))
        for (i in 0 until samples) {
            val y = 1.0 - (i + 0.5) / samples * 2.0
            val r = sqrt(1.0 - y * y)
            val theta = golden * i
            val x = cos(theta) * r
            val z = sin(theta) * r
            val lat = Math.toDegrees(asin(y))
            val lon = Math.toDegrees(atan2(x, z))
            if (mask.isLand(lat, lon)) {
                tmp[n * 3] = x.toFloat(); tmp[n * 3 + 1] = y.toFloat(); tmp[n * 3 + 2] = z.toFloat()
                n++
            }
        }
        land = tmp.copyOf(n * 3)
        landCount = n
    }

    /**
     * Proyecta `count` vectores aplicando rotación sobre el eje Y (`yaw`, radianes) e inclinación (`tilt`).
     * Escribe en `out` pares (x, y) en coordenadas unitarias (y hacia arriba) y en `depth` la
     * profundidad z ∈ [-1, 1] (positiva = cara visible). Devuelve el número escrito.
     */
    fun project(src: FloatArray, count: Int, yaw: Float, tilt: Float, out: FloatArray, depth: FloatArray, frontOnly: Boolean = true): Int {
        val cy = cos(yaw); val sy = sin(yaw)
        val ct = cos(tilt); val st = sin(tilt)
        var k = 0
        for (i in 0 until count) {
            val x = src[i * 3]; val y = src[i * 3 + 1]; val z = src[i * 3 + 2]
            val x1 = x * cy + z * sy
            val z1 = -x * sy + z * cy
            val y2 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            if (frontOnly && z2 < 0f) continue
            out[k * 2] = x1
            out[k * 2 + 1] = y2
            depth[k] = z2
            k++
        }
        return k
    }

    companion object {
        /** Yaw que coloca una longitud dada en el centro de la vista. */
        fun yawFacing(lonDeg: Double): Float = (-Math.toRadians(lonDeg)).toFloat()

        fun toUnit(lat: Double, lon: Double, out: FloatArray, offset: Int) {
            val la = Math.toRadians(lat)
            val lo = Math.toRadians(lon)
            out[offset] = (cos(la) * sin(lo)).toFloat()
            out[offset + 1] = sin(la).toFloat()
            out[offset + 2] = (cos(la) * cos(lo)).toFloat()
        }

        /** Puntos de un arco de círculo máximo entre dos vectores unitarios (para trazos de red). */
        fun greatArc(a: FloatArray, ai: Int, b: FloatArray, bi: Int, steps: Int, lift: Float, out: FloatArray) {
            val dot = (a[ai] * b[bi] + a[ai + 1] * b[bi + 1] + a[ai + 2] * b[bi + 2]).coerceIn(-1f, 1f)
            val omega = acos(dot)
            val so = sin(omega)
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val (wa, wb) = if (so < 1e-4f) (1 - t) to t else (sin((1 - t) * omega) / so) to (sin(t * omega) / so)
                val h = 1f + lift * sin(PI.toFloat() * t)
                out[s * 3] = (wa * a[ai] + wb * b[bi]) * h
                out[s * 3 + 1] = (wa * a[ai + 1] + wb * b[bi + 1]) * h
                out[s * 3 + 2] = (wa * a[ai + 2] + wb * b[bi + 2]) * h
            }
        }
    }
}
