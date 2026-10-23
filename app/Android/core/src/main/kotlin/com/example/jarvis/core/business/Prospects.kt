package com.example.jarvis.core.business

import com.example.jarvis.core.json.JarvisJson
import java.time.Instant

enum class ProspectStatus(val label: String) {
    NUEVO("Nuevo"),
    CONTACTADO("Contactado"),
    INTERESADO("Interesado"),
    NEGOCIANDO("Negociando"),
    CLIENTE("Cliente"),
    DESCARTADO("Descartado"),
}

/** Prospecto comercial persistente. Conserva una instantánea de la ficha con fuentes y estados. */
data class Prospect(
    val id: Long = 0,
    val businessId: String,
    val name: String,
    val category: String?,
    val address: String?,
    val province: String?,
    val canton: String?,
    val district: String?,
    val phone: String?,
    val website: String?,
    val email: String?,
    val lat: Double?,
    val lon: Double?,
    val locationApproximate: Boolean,
    val sourceName: String,
    val sourceUrl: String?,
    val foundAt: Instant,
    val status: ProspectStatus = ProspectStatus.NUEVO,
    val notes: String = "",
    val nextAction: String? = null,
    val updatedAt: Instant = foundAt,
    val snapshotJson: String,
) {
    fun snapshot(): Business? = runCatching { JarvisJson.decodeFromString(Business.serializer(), snapshotJson) }.getOrNull()

    companion object {
        fun fromBusiness(b: Business, now: Instant, note: String? = null, nextAction: String? = null): Prospect {
            val primary = b.sources.values.firstOrNull()
            return Prospect(
                businessId = b.id,
                name = b.displayName,
                category = b.category.value,
                address = b.address.value,
                province = b.province.value,
                canton = b.canton.value,
                district = b.district.value,
                phone = b.phone.value,
                website = b.website.value,
                email = b.email.value,
                lat = b.mapPoint?.lat,
                lon = b.mapPoint?.lon,
                locationApproximate = b.coordinates.status == FieldStatus.APPROXIMATE,
                sourceName = primary?.name ?: "Desconocida",
                sourceUrl = b.links.osm ?: primary?.url,
                foundAt = b.retrievedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now,
                notes = note.orEmpty(),
                nextAction = nextAction,
                updatedAt = now,
                snapshotJson = JarvisJson.encodeToString(Business.serializer(), b),
            )
        }
    }
}

interface ProspectRepository {
    suspend fun findByBusinessId(businessId: String): Prospect?
    suspend fun get(id: Long): Prospect?
    suspend fun insert(p: Prospect): Long
    suspend fun update(p: Prospect)
    suspend fun delete(id: Long): Boolean
    suspend fun all(): List<Prospect>
}

sealed interface SaveProspectResult {
    data class Saved(val prospect: Prospect) : SaveProspectResult
    data class AlreadyExists(val prospect: Prospect) : SaveProspectResult
}

class ProspectService(private val repo: ProspectRepository, private val now: () -> Instant) {
    /** Evita duplicados: un negocio (id de fuente) solo se guarda una vez. */
    suspend fun save(b: Business, note: String? = null, nextAction: String? = null): SaveProspectResult {
        repo.findByBusinessId(b.id)?.let { return SaveProspectResult.AlreadyExists(it) }
        // Los datos de Google Maps no se guardan de forma permanente (ADR-016).
        val p = Prospect.fromBusiness(b.withoutRestrictedSources(), now(), note, nextAction)
        val id = repo.insert(p)
        return SaveProspectResult.Saved(p.copy(id = id))
    }

    suspend fun update(id: Long, status: ProspectStatus? = null, note: String? = null, nextAction: String? = null): Prospect? {
        val p = repo.get(id) ?: return null
        val merged = when {
            note.isNullOrBlank() -> p.notes
            p.notes.isBlank() -> note.trim()
            else -> p.notes + "\n" + note.trim()
        }
        val updated = p.copy(
            status = status ?: p.status,
            notes = merged,
            nextAction = nextAction ?: p.nextAction,
            updatedAt = now(),
        )
        repo.update(updated)
        return updated
    }

    suspend fun list(status: ProspectStatus? = null, text: String? = null): List<Prospect> {
        val q = text?.lowercase()?.trim().orEmpty()
        return repo.all()
            .filter { status == null || it.status == status }
            .filter { q.isEmpty() || listOfNotNull(it.name, it.category, it.canton, it.province, it.notes).any { f -> f.lowercase().contains(q) } }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun organize(by: String): Map<String, List<Prospect>> {
        val all = repo.all()
        val key: (Prospect) -> String = when (by) {
            "province" -> { p -> p.province ?: "Provincia no disponible" }
            "canton" -> { p -> listOfNotNull(p.canton, p.province).joinToString(", ").ifBlank { "Cantón no disponible" } }
            "category" -> { p -> p.category ?: "Sin categoría" }
            "status" -> { p -> p.status.label }
            else -> throw IllegalArgumentException("Criterio no soportado: $by")
        }
        return all.groupBy(key).toSortedMap().mapValues { (_, v) -> v.sortedBy { it.name } }
    }
}
