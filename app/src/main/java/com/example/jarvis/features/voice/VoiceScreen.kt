package com.example.jarvis.features.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jarvis.ai.realtime.RealtimeConnectionState

@Composable
fun VoiceScreen(viewModel: VoiceViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Voz en Tiempo Real de JARVIS", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = when (state.voiceState) {
                        RealtimeConnectionState.IDLE, RealtimeConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
                        RealtimeConnectionState.CONNECTING, RealtimeConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                        RealtimeConnectionState.LISTENING -> MaterialTheme.colorScheme.errorContainer
                        RealtimeConnectionState.THINKING, RealtimeConnectionState.EXECUTING -> MaterialTheme.colorScheme.tertiaryContainer
                        RealtimeConnectionState.SPEAKING -> MaterialTheme.colorScheme.secondaryContainer
                        RealtimeConnectionState.INTERRUPTED, RealtimeConnectionState.ERROR -> MaterialTheme.colorScheme.error
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = state.voiceState.name, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = state.statusText, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { viewModel.toggleListening() },
            modifier = Modifier.height(50.dp)
        ) {
            Text(if (state.voiceState == RealtimeConnectionState.IDLE || state.voiceState == RealtimeConnectionState.DISCONNECTED) "Iniciar interacción por voz" else "Detener / Reiniciar")
        }
    }
}
