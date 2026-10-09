package com.example.jarvis.ai.context

data class BusinessResultMemory(
    val id: String,
    val name: String,
    val category: String = "negocio",
    val address: String = "",
    val phone: String = "",
    val website: String = "",
    val rating: Float? = null,
    val distanceKm: Float? = null,
    val sourceUrl: String = "",
    val retrievedAt: Long = System.currentTimeMillis()
) {
    fun isStale(ttlMs: Long = 3600000L): Boolean {
        return (System.currentTimeMillis() - retrievedAt) > ttlMs
    }
}
