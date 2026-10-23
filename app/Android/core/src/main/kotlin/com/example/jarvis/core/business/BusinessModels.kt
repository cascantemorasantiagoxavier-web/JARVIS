package com.example.jarvis.core.business

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Estado de un dato según sus fuentes (mismo vocabulario que el backend). */
@Serializable
enum class FieldStatus {
    @SerialName("found") FOUND,
    @SerialName("verified") VERIFIED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("conflict") CONFLICT,
    @SerialName("approximate") APPROXIMATE,
    ;

    val label: String
        get() = when (this) {
            FOUND -> "Encontrado en una fuente"
            VERIFIED -> "Comprobado en otra fuente"
            UNAVAILABLE -> "No disponible"
            CONFLICT -> "Dato contradictorio"
            APPROXIMATE -> "Ubicación aproximada"
        }
}

@Serializable
data class Alternative(val value: String, val source: String)

@Serializable
data class FieldValue<T>(
    val value: T? = null,
    val status: FieldStatus = FieldStatus.UNAVAILABLE,
    val sources: List<String> = emptyList(),
    val note: String? = null,
    val alternatives: List<Alternative> = emptyList(),
) {
    val isAvailable: Boolean get() = value != null && status != FieldStatus.UNAVAILABLE
}

@Serializable
data class LatLon(val lat: Double, val lon: Double)

@Serializable
data class BusinessLinks(val osm: String? = null, val geoUri: String? = null, val directions: String? = null)

@Serializable
data class SourceInfo(
    val name: String,
    val url: String? = null,
    val license: String? = null,
    val retrievedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class Business(
    val id: String,
    val name: FieldValue<String> = FieldValue(),
    val category: FieldValue<String> = FieldValue(),
    val brand: FieldValue<String> = FieldValue(),
    val address: FieldValue<String> = FieldValue(),
    val province: FieldValue<String> = FieldValue(),
    val canton: FieldValue<String> = FieldValue(),
    val district: FieldValue<String> = FieldValue(),
    val phone: FieldValue<String> = FieldValue(),
    val website: FieldValue<String> = FieldValue(),
    val email: FieldValue<String> = FieldValue(),
    val openingHours: FieldValue<String> = FieldValue(),
    val coordinates: FieldValue<LatLon> = FieldValue(),
    val links: BusinessLinks = BusinessLinks(),
    val sources: Map<String, SourceInfo> = emptyMap(),
    val retrievedAt: String? = null,
    val mergedIds: List<String> = emptyList(),
) {
    val displayName: String get() = name.value ?: "Negocio sin nombre en la fuente"

    /** Solo hay marcador de mapa si la fuente aporta coordenadas. */
    val mapPoint: LatLon? get() = coordinates.value.takeIf { coordinates.isAvailable }

    val locationLine: String?
        get() = listOfNotNull(district.value, canton.value, province.value).distinct().joinToString(", ").ifBlank { null }

    /** Texto plano para copiar al portapapeles: solo datos con fuente. */
    fun toClipboardText(): String = buildString {
        appendLine(displayName)
        category.value?.let { appendLine("Categoría: $it") }
        appendLine("Dirección: ${address.value ?: "No disponible"}")
        locationLine?.let { appendLine("Ubicación: $it") }
        appendLine("Teléfono: ${phone.value ?: "No disponible"}")
        appendLine("Sitio web: ${website.value ?: "No disponible"}")
        appendLine("Correo: ${email.value ?: "No disponible"}")
        mapPoint?.let { appendLine("Coordenadas: ${it.lat}, ${it.lon}" + if (coordinates.status == FieldStatus.APPROXIMATE) " (aprox.)" else "") }
        links.osm?.let { appendLine("Fuente: $it") }
        retrievedAt?.let { append("Consultado: $it") }
    }.trim()
}

@Serializable
data class CategoryInfo(val id: String, val label: String, val kind: String? = null)

@Serializable
data class AreaInfo(val name: String? = null, val displayName: String? = null, val osm: String? = null)

@Serializable
data class BusinessQuery(val category: String? = null, val location: String? = null)

@Serializable
data class BusinessSearchResult(
    val query: BusinessQuery = BusinessQuery(),
    val category: CategoryInfo,
    val area: AreaInfo? = null,
    val total: Int,
    val offset: Int = 0,
    val limit: Int = 20,
    val nextOffset: Int? = null,
    val results: List<Business>,
    val source: SourceInfo? = null,
    val retrievedAt: String,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class VerificationReport(
    val checked: List<CheckedSource> = emptyList(),
    val candidates: List<WebCandidate> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CheckedSource(val source: String, val phones: List<String> = emptyList(), val emails: List<String> = emptyList())

@Serializable
data class WebCandidate(val title: String, val url: String, val snippet: String? = null, val status: String = "candidate", val note: String? = null)

@Serializable
data class VerificationResult(val business: Business, val report: VerificationReport, val retrievedAt: String)
