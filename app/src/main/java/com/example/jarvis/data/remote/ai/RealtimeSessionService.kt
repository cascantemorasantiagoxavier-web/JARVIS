package com.example.jarvis.data.remote.ai

import com.example.jarvis.ai.realtime.RealtimeSessionInfo

interface RealtimeSessionService {
    suspend fun createSession(): Result<RealtimeSessionInfo>
}
