package com.example.jarvis.core.weather

import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class Place(val name: String, val admin: String? = null, val country: String? = null, val lat: Double, val lon: Double) {
    val label: String get() = listOfNotNull(name, admin).distinct().joinToString(", ")
}

data class WeatherNow(
    val place: Place,
    val temperatureC: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val observedAt: String,
    val source: String = "Open-Meteo.com (CC BY 4.0)",
) {
    val description: String get() = WeatherCodes.describe(weatherCode)
    val kind: WeatherKind get() = WeatherCodes.kind(weatherCode)
}

enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, STORM, SNOW }

/** Códigos WMO usados por Open-Meteo. */
object WeatherCodes {
    fun kind(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.CLEAR
        1, 2 -> WeatherKind.PARTLY_CLOUDY
        3 -> WeatherKind.CLOUDY
        45, 48 -> WeatherKind.FOG
        51, 53, 55, 56, 57 -> WeatherKind.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherKind.RAIN
        95, 96, 99 -> WeatherKind.STORM
        71, 73, 75, 77, 85, 86 -> WeatherKind.SNOW
        else -> WeatherKind.CLOUDY
    }

    fun describe(code: Int): String = when (code) {
        0 -> "despejado"
        1 -> "mayormente despejado"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "niebla"
        51, 53, 55 -> "llovizna"
        56, 57 -> "llovizna helada"
        61 -> "lluvia ligera"
        63 -> "lluvia"
        65 -> "lluvia fuerte"
        66, 67 -> "lluvia helada"
        80, 81 -> "chubascos"
        82 -> "chubascos fuertes"
        95 -> "tormenta"
        96, 99 -> "tormenta con granizo"
        71, 73, 75, 77, 85, 86 -> "nieve"
        else -> "condición desconocida"
    }
}

class WeatherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Cliente de Open-Meteo (sin clave). Se llama directamente desde el teléfono para que el
 * clima funcione aunque el backend no esté disponible. Uso no comercial; ver condiciones.
 */
class WeatherClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val forecastUrl: String = "https://api.open-meteo.com/v1/forecast",
    private val geocodingUrl: String = "https://geocoding-api.open-meteo.com/v1/search",
) {
    @Serializable private data class GeoResults(val results: List<GeoItem> = emptyList())
    @Serializable private data class GeoItem(val name: String, val latitude: Double, val longitude: Double, val admin1: String? = null, val admin2: String? = null, val country: String? = null)
    @Serializable private data class Forecast(val current: Current? = null)
    @Serializable private data class Current(val time: String, val temperature_2m: Double, val weather_code: Int, val is_day: Int = 1)

    suspend fun geocode(name: String, countryCode: String? = "CR"): List<Place> {
        val url = geocodingUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", name.trim())
            .addQueryParameter("count", "5")
            .addQueryParameter("language", "es")
            .addQueryParameter("format", "json")
            .apply { if (countryCode != null) addQueryParameter("countryCode", countryCode) }
            .build()
        val body = get(url.toString())
        return JarvisJson.decodeFromString(GeoResults.serializer(), body).results.map {
            Place(it.name, it.admin2?.let { a2 -> if (a2 != it.name) a2 else null } ?: it.admin1, it.country, it.latitude, it.longitude)
        }
    }

    suspend fun current(place: Place): WeatherNow {
        val url = forecastUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", place.lat.toString())
            .addQueryParameter("longitude", place.lon.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("timezone", "auto")
            .build()
        val f = JarvisJson.decodeFromString(Forecast.serializer(), get(url.toString()))
        val c = f.current ?: throw WeatherException("Open-Meteo no devolvió datos actuales.")
        return WeatherNow(place, c.temperature_2m, c.weather_code, c.is_day == 1, c.time)
    }

    private suspend fun get(url: String): String {
        val resp = try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "JARVIS-Assistant").build()).await()
        } catch (e: IOException) {
            throw WeatherException("No se pudo consultar el clima (sin conexión).", e)
        }
        return withContext(Dispatchers.IO) {
            resp.use {
                if (!it.isSuccessful) throw WeatherException("El servicio de clima respondió HTTP ${it.code}.")
                it.body?.string().orEmpty()
            }
        }
    }
}
