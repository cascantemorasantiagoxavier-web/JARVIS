package com.example.jarvis.features.chat

import androidx.lifecycle.viewModelScope
import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.di.JarvisRequest
import com.example.jarvis.core.di.RequestSource
import com.example.jarvis.core.viewmodel.BaseViewModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val sender: String, val text: String)

data class ChatUiState(
    val messages: List<ChatMessage> = listOf(ChatMessage("JARVIS", "¡Hola, Santiago! ¿Cómo puedo ayudarte hoy?")),
    val input: String = "",
    val isLoading: Boolean = false
)

class ChatViewModel : BaseViewModel<ChatUiState>(ChatUiState()) {

    fun onInputChanged(newInput: String) {
        _uiState.update { it.copy(input = newInput) }
    }

    fun sendMessage() {
        val currentInput = _uiState.value.input
        if (currentInput.isBlank()) return

        println("[JARVIS_E2E] CHATVIEWMODEL_SEND_MESSAGE=START")
        println("[JARVIS_E2E] REQUEST_SOURCE=CHAT")

        val userMessage = ChatMessage("Usuario", currentInput)
        _uiState.update { it.copy(messages = it.messages + userMessage, input = "", isLoading = true) }

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val jarvisReq = JarvisRequest(text = currentInput, source = RequestSource.CHAT)
            val response = JarvisBrain.processRequest(jarvisReq)
            val totalLatency = System.currentTimeMillis() - startTime

            println("[JARVIS_E2E] TOTAL_LATENCY=$totalLatency")
            if (response.success && !response.text.contains("Error", true) && !response.text.contains("No fue posible", true)) {
                println("[JARVIS_E2E] CHATVIEWMODEL_TO_GEMINI=PASS")
            } else {
                println("[JARVIS_E2E] CHATVIEWMODEL_TO_GEMINI=FAIL")
            }

            val aiMessage = ChatMessage("JARVIS", response.text)
            _uiState.update { it.copy(messages = it.messages + aiMessage, isLoading = false) }
        }
    }
}
