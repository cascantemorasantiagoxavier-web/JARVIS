package com.example.jarvis.features.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jarvis.ai.realtime.RealtimeConnectionState
import com.example.jarvis.ui.hud.AssistantState
import com.example.jarvis.ui.hud.JarvisCore
import com.example.jarvis.ui.theme.*

@Composable
fun JarvisVoiceScreen(viewModel: VoiceViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleListening()
        }
    }

    val hudState = when (state.voiceState) {
        RealtimeConnectionState.IDLE, RealtimeConnectionState.DISCONNECTED -> AssistantState.IDLE
        RealtimeConnectionState.CONNECTING, RealtimeConnectionState.CONNECTED -> AssistantState.IDLE
        RealtimeConnectionState.LISTENING -> AssistantState.LISTENING
        RealtimeConnectionState.THINKING -> AssistantState.THINKING
        RealtimeConnectionState.EXECUTING -> AssistantState.EXECUTING
        RealtimeConnectionState.SPEAKING -> AssistantState.SPEAKING
        RealtimeConnectionState.INTERRUPTED, RealtimeConnectionState.ERROR -> AssistantState.ERROR
    }

    val stateText = when (state.voiceState) {
        RealtimeConnectionState.IDLE, RealtimeConnectionState.DISCONNECTED -> "LISTO / EN ESPERA"
        RealtimeConnectionState.CONNECTING -> "ESTABLECIENDO CONEXIÓN SEGURA..."
        RealtimeConnectionState.CONNECTED, RealtimeConnectionState.LISTENING -> "ESCUCHANDO TU VOZ..."
        RealtimeConnectionState.THINKING -> "PROCESANDO EN CEREBRO NEURONAL..."
        RealtimeConnectionState.EXECUTING -> "EJECUTANDO ACCIÓN DE HERRAMIENTA..."
        RealtimeConnectionState.SPEAKING -> "HABLANDO Y SINTETIZANDO RESPUESTA..."
        RealtimeConnectionState.INTERRUPTED, RealtimeConnectionState.ERROR -> "ERROR O INTERRUPCIÓN DE VOZ"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackground)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .background(HudSurfaceDark, RoundedCornerShape(8.dp))
                    .border(1.dp, HudCyanNeon, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = "🎙️ HUD DE VOZ REAL DE JARVIS", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
            }

            Spacer(modifier = Modifier.height(20.dp))

            JarvisCore(state = hudState, size = 220, audioLevel = state.audioLevel)

            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "ESTADO: $stateText", style = MaterialTheme.typography.bodyLarge, color = HudGoldAccent)

            if (state.userQuery.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .background(HudSurface, RoundedCornerShape(6.dp))
                        .border(1.dp, HudCyanNeon.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "TÚ: \"${state.userQuery}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudCyanNeon
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = state.statusText, style = MaterialTheme.typography.bodyMedium, color = Color.White)

            if (state.voiceState == RealtimeConnectionState.LISTENING) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.audioLevel },
                    modifier = Modifier.fillMaxWidth(0.6f).height(6.dp),
                    color = HudPrimaryAmber,
                    trackColor = HudSurface
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.toggleListening()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = HudCyanNeon),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(50.dp).fillMaxWidth(0.7f)
            ) {
                Text(
                    text = if (state.voiceState == RealtimeConnectionState.IDLE || state.voiceState == RealtimeConnectionState.DISCONNECTED) "Iniciar Escucha de Voz" else "Detener Escucha",
                    color = Color.Black,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
