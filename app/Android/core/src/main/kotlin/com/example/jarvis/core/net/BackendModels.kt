package com.example.jarvis.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class HealthFeatures(
    val realtime: Boolean = false,
    val realtimeModel: String? = null,
    val chat: Boolean = false,
    val webSearch: List<String> = emptyList(),
    val business: Boolean = false,
)

@Serializable
data class HealthResponse(val status: String, val version: String? = null, val time: String? = null, val features: HealthFeatures = HealthFeatures())

@Serializable
data class RealtimeSessionRequest(
    val model: String? = null,
    val voice: String? = null,
    val speed: Double? = null,
    val instructions: String? = null,
)

@Serializable
data class RealtimeSecret(
    val mode: String,
    val clientSecret: String,
    val expiresAt: Long? = null,
    val model: String,
    val voice: String,
    val callsUrl: String,
)

@Serializable
data class RealtimeCallRequest(
    val sdp: String,
    val model: String? = null,
    val voice: String? = null,
    val speed: Double? = null,
    val instructions: String? = null,
    val maxSessionMinutes: Int? = null,
)

@Serializable
data class RealtimeCallAnswer(
    val mode: String,
    val sdp: String,
    val callId: String? = null,
    val model: String,
    val voice: String,
    val maxSessionMinutes: Int? = null,
)

@Serializable
data class ChatToolSpec(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class ChatRequest(
    val instructions: String,
    val input: List<JsonObject>,
    val tools: List<ChatToolSpec> = emptyList(),
    val mode: String = "BALANCED",
)

@Serializable
data class ChatToolCall(val callId: String, val name: String, val arguments: String)

@Serializable
data class ChatUsage(val inputTokens: Long = 0, val outputTokens: Long = 0)

@Serializable
data class ChatResponse(
    val status: String = "unknown",
    val incompleteReason: String? = null,
    val text: String = "",
    val toolCalls: List<ChatToolCall> = emptyList(),
    val outputItems: List<JsonObject> = emptyList(),
    val usage: ChatUsage? = null,
    val model: String? = null,
)

@Serializable
data class WebSearchRequest(val query: String, val maxResults: Int? = null, val domains: List<String>? = null, val lang: String? = null)

@Serializable
data class WebResult(
    val title: String,
    val url: String,
    val snippet: String? = null,
    val source: String? = null,
    val publishedAt: String? = null,
    val retrievedAt: String,
    val provider: String? = null,
)

@Serializable
data class WebSearchResult(
    val query: String,
    val provider: String? = null,
    val providerLabel: String? = null,
    val coverage: String = "web",
    val retrievedAt: String,
    val results: List<WebResult> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class WebFetchRequest(val url: String, val maxChars: Int? = null)

@Serializable
data class PageContacts(val emails: List<String> = emptyList(), val phones: List<String> = emptyList())

@Serializable
data class PageContent(
    val url: String,
    val finalUrl: String? = null,
    val title: String? = null,
    val description: String? = null,
    val publishedAt: String? = null,
    val text: String = "",
    val truncated: Boolean = false,
    val contacts: PageContacts = PageContacts(),
    val retrievedAt: String,
    val untrusted: Boolean = true,
)

@Serializable
data class NearSpec(val lat: Double, val lon: Double, val radiusM: Int? = null)

@Serializable
data class BusinessSearchRequest(
    val category: String,
    val location: String? = null,
    val near: NearSpec? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

@Serializable
data class UsageCounters(val today: Int = 0, val lastHour: Int = 0, val maxPerHour: Int? = null, val maxPerDay: Int? = null)

@Serializable
data class BackendUsage(val realtime: UsageCounters = UsageCounters(), val chat: UsageCounters = UsageCounters(), val note: String? = null)

@Serializable
data class ApiErrorBody(val error: ApiError? = null)

@Serializable
data class ApiError(val code: String = "unknown", val message: String = "")
