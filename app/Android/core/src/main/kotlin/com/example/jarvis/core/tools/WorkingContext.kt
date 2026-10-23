package com.example.jarvis.core.tools

import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.net.WebSearchResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Contexto de trabajo de la sesión (no persistente): últimos resultados para resolver
 * referencias como "el segundo", "ese supermercado" o "muéstralos en el mapa".
 */
class WorkingContext {
    @Volatile var lastBusinessSearch: BusinessSearchResult? = null
        private set
    @Volatile var lastWebSearch: WebSearchResult? = null
    @Volatile var selectedBusinessId: String? = null
    private val businesses = ConcurrentHashMap<String, Business>()

    fun rememberBusinessSearch(result: BusinessSearchResult) {
        lastBusinessSearch = result
        result.results.forEach { businesses[it.id] = it }
    }

    fun rememberBusiness(b: Business) {
        businesses[b.id] = b
    }

    fun business(id: String): Business? = businesses[id]

    fun lastBusinesses(): List<Business> = lastBusinessSearch?.results.orEmpty()

    /** Resuelve referencias ordinales ("el primero", "la tercera") sobre la última lista. */
    fun businessByOrdinal(index: Int): Business? = lastBusinesses().getOrNull(index)

    /** Resumen breve para el contexto del modelo, sin datos privados. */
    fun summaryForModel(maxItems: Int = 12): String? {
        val last = lastBusinessSearch ?: return null
        val lines = last.results.take(maxItems).mapIndexed { i, b ->
            "${i + 1}. ${b.name.value ?: "(sin nombre)"} [${b.id}]" + (b.canton.value?.let { " — $it" } ?: "")
        }
        return "Últimos negocios mostrados (${last.category.label}, ${last.total} en total):\n" + lines.joinToString("\n")
    }
}
