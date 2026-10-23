package com.example.jarvis.core.realtime

import com.example.jarvis.core.json.JarvisJson
import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.long
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.tools.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Estados de la voz (más detallados que los del núcleo). */
enum class VoiceState { OFF, WAKE_WORD, CONNECTING, LISTENING, USER_SPEAKING, THINKING, EXECUTING, SPEAKING, ERROR }

data class FunctionCallItem(val callId: String, val name: String, val arguments: String)

data class UsageDelta(
    val inputTokens: Long,
    val outputTokens: Long,
    val audioIn: Long,
    val textIn: Long,
    val cachedIn: Long,
    val cachedAudioIn: Long,
    val audioOut: Long,
    val textOut: Long,
)

data class RateLimit(val name: String?, val limit: Long?, val remaining: Long?, val resetSeconds: Double?)

/** Eventos del servidor Realtime (API GA) relevantes para JARVIS. */
sealed interface ServerEvent {
    data class SessionCreated(val model: String?, val voice: String?) : ServerEvent
    data object SessionUpdated : ServerEvent
    data object SpeechStarted : ServerEvent
    data object SpeechStopped : ServerEvent
    data class ResponseCreated(val responseId: String?) : ServerEvent
    data class TranscriptDelta(val responseId: String?, val delta: String) : ServerEvent
    data class TranscriptDone(val responseId: String?, val transcript: String) : ServerEvent
    data class TextDone(val responseId: String?, val text: String) : ServerEvent
    data object OutputAudioStarted : ServerEvent
    data object OutputAudioStopped : ServerEvent
    data object OutputAudioCleared : ServerEvent
    data class FunctionCallArgsDone(val responseId: String?, val call: FunctionCallItem) : ServerEvent
    data class ResponseDone(
        val responseId: String?,
        val status: String?,
        val statusReason: String?,
        val errorMessage: String?,
        val functionCalls: List<FunctionCallItem>,
        val hadAudio: Boolean,
        val usage: UsageDelta?,
    ) : ServerEvent
    data class UserTranscript(val itemId: String?, val transcript: String) : ServerEvent
    data object UserTranscriptFailed : ServerEvent
    data class RateLimits(val limits: List<RateLimit>) : ServerEvent
    data class Error(val type: String?, val code: String?, val message: String) : ServerEvent
    data object IdleTimeoutTriggered : ServerEvent
    data class Other(val type: String) : ServerEvent
}

/**
 * Construcción y lectura de mensajes del canal de datos "oai-events".
 * Nombres de eventos y forma de la sesión verificados contra los tipos del SDK oficial
 * `openai` 7.43.0 (archivos de tipos resources/realtime), ver JARVIS-KB/07-RESEARCH/openai-realtime.md.
 */
object RealtimeProtocol {
    const val DATA_CHANNEL = "oai-events"

    /** Configura instrucciones y herramientas tras conectar. La voz se fija al crear la sesión. */
    fun sessionUpdate(instructions: String, tools: List<ToolDefinition>, speed: Double? = null): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("instructions", instructions)
            put("tool_choice", "auto")
            putJsonArray("tools") {
                for (t in tools) addJsonObject {
                    put("type", "function")
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parameters)
                }
            }
            if (speed != null) putJsonObject("audio") { putJsonObject("output") { put("speed", speed) } }
        }
    }.toString()

    fun functionCallOutput(callId: String, output: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }.toString()

    fun responseCreate(): String = """{"type":"response.create"}"""
    fun responseCancel(): String = """{"type":"response.cancel"}"""

    /** Solo WebRTC/SIP: corta el audio que el servidor aún está enviando. */
    fun outputAudioClear(): String = """{"type":"output_audio_buffer.clear"}"""

    fun userText(text: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "input_text")
                    put("text", text)
                }
            }
        }
    }.toString()

    fun parse(raw: String): ServerEvent {
        val o = runCatching { JarvisJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return ServerEvent.Other("invalid_json")
        val type = o.str("type") ?: return ServerEvent.Other("missing_type")
        return when (type) {
            "session.created" -> {
                val s = o.obj("session")
                ServerEvent.SessionCreated(s?.str("model"), s?.obj("audio")?.obj("output")?.str("voice"))
            }
            "session.updated" -> ServerEvent.SessionUpdated
            "input_audio_buffer.speech_started" -> ServerEvent.SpeechStarted
            "input_audio_buffer.speech_stopped" -> ServerEvent.SpeechStopped
            "input_audio_buffer.timeout_triggered" -> ServerEvent.IdleTimeoutTriggered
            "response.created" -> ServerEvent.ResponseCreated(o.obj("response")?.str("id"))
            "response.output_audio_transcript.delta" -> ServerEvent.TranscriptDelta(o.str("response_id"), o.str("delta").orEmpty())
            "response.output_audio_transcript.done" -> ServerEvent.TranscriptDone(o.str("response_id"), o.str("transcript").orEmpty())
            "response.output_text.done" -> ServerEvent.TextDone(o.str("response_id"), o.str("text").orEmpty())
            "output_audio_buffer.started" -> ServerEvent.OutputAudioStarted
            "output_audio_buffer.stopped" -> ServerEvent.OutputAudioStopped
            "output_audio_buffer.cleared" -> ServerEvent.OutputAudioCleared
            "response.function_call_arguments.done" -> ServerEvent.FunctionCallArgsDone(
                o.str("response_id"),
                FunctionCallItem(o.str("call_id").orEmpty(), o.str("name").orEmpty(), o.str("arguments") ?: "{}"),
            )
            "response.done" -> parseResponseDone(o)
            "conversation.item.input_audio_transcription.completed" -> ServerEvent.UserTranscript(o.str("item_id"), o.str("transcript").orEmpty())
            "conversation.item.input_audio_transcription.failed" -> ServerEvent.UserTranscriptFailed
            "rate_limits.updated" -> ServerEvent.RateLimits(
                o.arr("rate_limits")?.mapNotNull { it as? JsonObject }?.map {
                    RateLimit(it.str("name"), it.long("limit"), it.long("remaining"), (it["reset_seconds"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull())
                }.orEmpty(),
            )
            "error" -> {
                val e = o.obj("error")
                ServerEvent.Error(e?.str("type"), e?.str("code"), e?.str("message") ?: "Error desconocido de Realtime")
            }
            else -> ServerEvent.Other(type)
        }
    }

    private fun parseResponseDone(o: JsonObject): ServerEvent.ResponseDone {
        val r = o.obj("response")
        val output = r?.arr("output")?.mapNotNull { it as? JsonObject }.orEmpty()
        val calls = output.filter { it.str("type") == "function_call" }.mapNotNull {
            val id = it.str("call_id") ?: return@mapNotNull null
            FunctionCallItem(id, it.str("name").orEmpty(), it.str("arguments") ?: "{}")
        }
        val hadAudio = output.any { item -> item.arr("content")?.any { (it as? JsonObject)?.str("type") == "output_audio" } == true }
        val details = r?.obj("status_details")
        val u = r?.obj("usage")
        val usage = u?.let {
            val inD = it.obj("input_token_details")
            val outD = it.obj("output_token_details")
            UsageDelta(
                inputTokens = it.long("input_tokens") ?: 0,
                outputTokens = it.long("output_tokens") ?: 0,
                audioIn = inD?.long("audio_tokens") ?: 0,
                textIn = inD?.long("text_tokens") ?: 0,
                cachedIn = inD?.long("cached_tokens") ?: 0,
                cachedAudioIn = inD?.obj("cached_tokens_details")?.long("audio_tokens") ?: 0,
                audioOut = outD?.long("audio_tokens") ?: 0,
                textOut = outD?.long("text_tokens") ?: 0,
            )
        }
        return ServerEvent.ResponseDone(
            responseId = r?.str("id"),
            status = r?.str("status"),
            statusReason = details?.str("reason"),
            errorMessage = details?.obj("error")?.str("message"),
            functionCalls = calls,
            hadAudio = hadAudio,
            usage = usage,
        )
    }
}
