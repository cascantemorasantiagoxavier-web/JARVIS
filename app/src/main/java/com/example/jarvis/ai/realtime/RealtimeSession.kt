package com.example.jarvis.ai.realtime

data class RealtimeSessionConfig(
    val model: String,
    val token: String,
    val timeoutMillis: Long = 30000L
)

data class RealtimeSessionInfo(
    val sessionId: String,
    val clientSecret: String,
    val expiresAt: Long
)

data class RealtimeAudioChunk(
    val data: ByteArray,
    val sampleRate: Int = 24000
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RealtimeAudioChunk
        return data.contentEquals(other.data) && sampleRate == other.sampleRate
    }
    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}

data class RealtimeError(
    val code: Int,
    val message: String,
    val throwable: Throwable? = null
)
