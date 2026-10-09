package com.example.jarvis.ai.orchestrator

import com.example.jarvis.ai.realtime.RealtimeClient
import com.example.jarvis.ai.realtime.RealtimeEvent
import com.example.jarvis.ai.orchestrator.models.VoiceCommand
import com.example.jarvis.ai.orchestrator.models.VoiceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisVoiceOrchestratorService(
    private val realtimeClient: RealtimeClient,
    private val bridge: VoiceOrchestratorBridge,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    init {
        scope.launch {
            realtimeClient.events().collect { event ->
                when (event) {
                    is RealtimeEvent.TextReceived -> {
                        val command = VoiceCommand(text = event.text)
                        val response = bridge.processVoiceCommand(command)
                        realtimeClient.sendText(response.text)
                    }
                    else -> {}
                }
            }
        }
    }

    suspend fun handleTextCommand(text: String): VoiceResponse {
        val command = VoiceCommand(text = text)
        return bridge.processVoiceCommand(command)
    }
}
