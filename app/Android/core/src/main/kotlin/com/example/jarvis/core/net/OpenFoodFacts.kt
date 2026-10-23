package com.example.jarvis.core.net

import com.example.jarvis.core.json.JarvisJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class Product(
    val barcode: String,
    val name: String?,
    val brands: String?,
    val quantity: String?,
    val categories: String?,
    val sourceUrl: String,
)

sealed interface ProductLookupResult {
    data class Found(val product: Product) : ProductLookupResult
    data object NotFound : ProductLookupResult
    data class Error(val message: String) : ProductLookupResult
}

/** Búsqueda de productos por código de barras. */
fun interface ProductLookup {
    suspend fun lookup(barcode: String): ProductLookupResult
}

/**
 * Open Food Facts (base abierta, ODbL, sin clave). Se consulta desde el teléfono. Muchos
 * productos no están: en ese caso se dice "no encontrado", nunca se inventa.
 */
class OpenFoodFactsClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://world.openfoodfacts.org",
) : ProductLookup {
    @Serializable private data class Resp(val status: Int = 0, val product: P? = null)
    @Serializable private data class P(val product_name: String? = null, val brands: String? = null, val quantity: String? = null, val categories: String? = null)

    override suspend fun lookup(barcode: String): ProductLookupResult {
        if (!barcode.all { it.isDigit() } || barcode.length !in 8..14) return ProductLookupResult.Error("Código de barras inválido.")
        val url = "${baseUrl.trimEnd('/')}/api/v2/product/$barcode.json?fields=product_name,brands,quantity,categories"
        val resp = try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "JARVIS-Assistant/0.1 (asistente personal)").build()).await()
        } catch (e: IOException) {
            return ProductLookupResult.Error("No se pudo consultar Open Food Facts (sin conexión).")
        }
        return withContext(Dispatchers.IO) {
            resp.use { r ->
                if (r.code == 404) return@withContext ProductLookupResult.NotFound
                if (!r.isSuccessful) return@withContext ProductLookupResult.Error("Open Food Facts respondió HTTP ${r.code}.")
                val body = runCatching { JarvisJson.decodeFromString(Resp.serializer(), r.body?.string().orEmpty()) }.getOrNull()
                    ?: return@withContext ProductLookupResult.Error("Respuesta inesperada de Open Food Facts.")
                val p = body.product
                if (body.status != 1 || p == null) ProductLookupResult.NotFound
                else ProductLookupResult.Found(Product(barcode, p.product_name?.takeIf { it.isNotBlank() }, p.brands, p.quantity, p.categories, "${baseUrl.trimEnd('/')}/product/$barcode"))
            }
        }
    }
}
