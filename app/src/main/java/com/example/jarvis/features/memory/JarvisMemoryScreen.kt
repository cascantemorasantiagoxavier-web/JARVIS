package com.example.jarvis.features.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.jarvis.ui.theme.*

@Composable
fun JarvisMemoryScreen(viewModel: MemoryViewModel = remember { MemoryViewModel() }) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackground)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HudSurfaceDark, RoundedCornerShape(6.dp))
                    .border(1.dp, HudCyanNeon.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "🧠 MEMORIA JERÁRQUICA Y CONOCIMIENTO", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                Text(text = "SINC: 100%", style = MaterialTheme.typography.labelSmall, color = HudSuccessGreen)
            }
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { HudMemoryCard(title = "WORKING MEMORY", desc = state.workingMemoryStatus) }
                item { HudMemoryCard(title = "SESSION MEMORY", desc = state.sessionMemoryStatus) }
                item { HudMemoryCard(title = "LONG-TERM MEMORY", desc = state.longTermMemoryStatus) }
                item { HudMemoryCard(title = "KNOWLEDGE & OBSIDIAN", desc = state.knowledgeStatus) }
            }
        }
    }
}

@Composable
fun HudMemoryCard(title: String, desc: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HudSurface),
        border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = HudCyanNeon)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = HudTextMuted)
        }
    }
}
