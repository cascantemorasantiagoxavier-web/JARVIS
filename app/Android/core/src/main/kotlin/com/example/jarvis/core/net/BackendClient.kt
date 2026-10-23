package com.example.jarvis.core.net

import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.VerificationResult
import com.example.jarvis.core.json.JarvisJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Configuración del servidor elegida por el usuario en Ajustes. */
data class BackendConfig(val baseUrl: String, val token: String) {
    val isConfigured: Boolean get() = baseUrl.toHttpUrlOrNull() != null && token.length >= 24
}

/** Error de backend con código estable y mensaje para el usuario en español. */
class BackendException(val code: String, val userMessage: String, val httpStatus: Int? = null, cause: Throwable? = null) :
    Exception("$code: $userMessage", cause)

/**
 * Cliente del backend de JARVIS. Toda comunicación con OpenAI, búsqueda web y OSM pasa
 * por aquí; la app nunca contiene la clave de OpenAI.
 */
class BackendClient(
    private val config: () -> BackendConfig,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    private val http = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun health(): HealthResponse = get("/health", HealthResponse.serializer(), auth = false)

    suspend fun realtimeSession(req: RealtimeSessionRequest): RealtimeSecret =
        post("/api/realtime/session", req, RealtimeSessionRequest.serializer(), RealtimeSecret.serializer())

    suspend fun realtimeCall(req: RealtimeCallRequest): RealtimeCallAnswer =
        post("/api/realtime/call", req, RealtimeCallRequest.serializer(), RealtimeCallAnswer.serializer())

    suspend fun realtimeHangup(callId: String) {
        val safe = URLEncoder.encode(callId, "UTF-8")
        execute(request("/api/realtime/call/$safe/hangup", "{}"), auth = true)
    }

    suspend fun usage(): BackendUsage = get("/api/usage", BackendUsage.serializer(), auth = true)

    suspend fun chat(req: ChatRequest): ChatResponse = post("/api/chat", req, ChatRequest.serializer(), ChatResponse.serializer())

    suspend fun webSearch(req: WebSearchRequest): WebSearchResult =
        post("/api/web/search", req, WebSearchRequest.serializer(), WebSearchResult.serializer())

    suspend fun fetchPage(req: WebFetchRequest): PageContent =
        post("/api/web/fetch", req, WebFetchRequest.serializer(), PageContent.serializer())

    suspend fun businessSearch(req: BusinessSearchRequest): BusinessSearchResult =
        post("/api/business/search", req, BusinessSearchRequest.serializer(), BusinessSearchResult.serializer())

    suspend fun business(id: String): Business =
        get("/api/business/${URLEncoder.encode(id, "UTF-8")}", Business.serializer(), auth = true)

    suspend fun verifyBusiness(id: String): VerificationResult {
        val body = execute(request("/api/business/${URLEncoder.encode(id, "UTF-8")}/verify", "{}"), auth = true)
        return decode(body, VerificationResult.serializer())
    }

    // ---- infraestructura ----

    private fun cfg(): BackendConfig {
        val c = config()
        if (!c.isConfigured) {
            throw BackendException("backend_not_configured", "El servidor de JARVIS no está configurado. Indica la URL y el token en Ajustes.")
        }
        return c
    }

    private fun url(path: String): String = cfg().baseUrl.trimEnd('/') + path

    private fun request(path: String, json: String): Request =
        Request.Builder().url(url(path)).post(json.toRequestBody(jsonType)).build()

    private suspend fun <T> get(path: String, out: KSerializer<T>, auth: Boolean): T {
        val req = Request.Builder().url(url(path)).get().build()
        return decode(execute(req, auth), out)
    }

    private suspend fun <I, O> post(path: String, body: I, inS: KSerializer<I>, out: KSerializer<O>): O {
        val json = JarvisJson.encodeToString(inS, body)
        return decode(execute(request(path, json), auth = true), out)
    }

    private fun <T> decode(body: String, s: KSerializer<T>): T = try {
        JarvisJson.decodeFromString(s, body)
    } catch (e: Exception) {
        throw BackendException("bad_response", "El servidor respondió con un formato inesperado.", cause = e)
    }

    private suspend fun execute(req: Request, auth: Boolean): String {
        val finalReq = if (auth) req.newBuilder().header("Authorization", "Bearer ${cfg().token}").build() else req
        val response = try {
            http.newCall(finalReq).await()
        } catch (e: UnknownHostException) {
            throw BackendException("offline", "No hay conexión con el servidor de JARVIS (sin Internet o dirección incorrecta).", cause = e)
        } catch (e: SocketTimeoutException) {
            throw BackendException("timeout", "El servidor de JARVIS tardó demasiado en responder.", cause = e)
        } catch (e: IOException) {
            throw BackendException("unreachable", "No se pudo contactar con el servidor de JARVIS.", cause = e)
        }
        return withContext(Dispatchers.IO) {
            response.use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw mapError(r.code, text)
                text
            }
        }
    }

    private fun mapError(status: Int, text: String): BackendException {
        val api = runCatching { JarvisJson.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
        val code = api?.code ?: "http_$status"
        val message = when {
            status == 401 -> "El servidor rechazó el token de la app. Revisa el token en Ajustes."
            !api?.message.isNullOrBlank() -> api!!.message
            status == 429 -> "Se alcanzó un límite de uso. Inténtalo más tarde."
            status >= 500 -> "El servidor tuvo un problema (HTTP $status)."
            else -> "Petición rechazada por el servidor (HTTP $status)."
        }
        return BackendException(code, message, status)
    }
}

/** Ejecuta una llamada OkHttp de forma suspendida y cancelable. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
