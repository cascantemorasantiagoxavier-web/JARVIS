package com.example.jarvis.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jarvis.ai.realtime.RealtimeConnectionState
import com.example.jarvis.features.voice.VoiceViewModel
import com.example.jarvis.ui.hud.*
import com.example.jarvis.ui.theme.*

@Composable
fun JarvisHomeScreen(
    voiceViewModel: VoiceViewModel = viewModel(),
    onNavigate: (String) -> Unit = {}
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    val assistantState = when (voiceState.voiceState) {
        RealtimeConnectionState.IDLE, RealtimeConnectionState.DISCONNECTED -> AssistantState.IDLE
        RealtimeConnectionState.CONNECTING, RealtimeConnectionState.CONNECTED -> AssistantState.IDLE
        RealtimeConnectionState.LISTENING -> AssistantState.LISTENING
        RealtimeConnectionState.THINKING -> AssistantState.THINKING
        RealtimeConnectionState.EXECUTING -> AssistantState.EXECUTING
        RealtimeConnectionState.SPEAKING -> AssistantState.SPEAKING
        RealtimeConnectionState.INTERRUPTED, RealtimeConnectionState.ERROR -> AssistantState.ERROR
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackground)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top HUD Header
            ReferenceTopHeader(state = assistantState)

            // 2. Central Immersive Holographic Sphere (Hero Centered)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(320.dp)
                        .background(HudSurfaceDark.copy(alpha = 0.4f), RoundedCornerShape(160.dp))
                        .border(1.5.dp, HudGoldAccent.copy(alpha = 0.4f), RoundedCornerShape(160.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    JarvisCore(
                        state = assistantState,
                        size = 300,
                        audioLevel = voiceState.audioLevel
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .background(HudSurfaceDark, RoundedCornerShape(8.dp))
                        .border(1.dp, HudGoldAccent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = voiceState.statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = HudGoldAccent
                    )
                }
            }

            // 3. Bottom Voice Command Bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ReferenceBottomBar(
                    voiceViewModel = voiceViewModel,
                    onMessage = { msg -> snackbarMessage = msg }
                )

                snackbarMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = HudGoldAccent
                    )
                }
            }
        }
    }
}
