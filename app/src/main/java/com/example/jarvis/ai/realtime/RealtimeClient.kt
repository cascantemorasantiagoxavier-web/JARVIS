package com.example.jarvis.ai.realtime

import kotlinx.coroutines.flow.Flow

interface RealtimeClient {
    suspend fun connect(session: RealtimeSessionConfig)
    suspend fun disconnect()
    suspend fun sendText(text: String)
    suspend fun sendAudio(audio: ByteArray)
    suspend fun commitAndCreateResponse()
    fun events(): Flow<RealtimeEvent>
}
