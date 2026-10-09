package com.example.jarvis.features.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.jarvis.ui.theme.*

@Composable
fun JarvisTasksScreen(viewModel: TasksViewModel = remember { TasksViewModel() }) {
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
                Text(text = "📋 TAREAS ACTIVAS Y PRODUCTIVIDAD", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                Text(text = "TOTAL: ${state.tasks.size}", style = MaterialTheme.typography.labelSmall, color = HudGoldAccent)
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HudCyanNeon)
                }
            } else if (state.tasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "NO HAY TAREAS PENDIENTES - SISTEMA ÓPTIMO", style = MaterialTheme.typography.bodyMedium, color = HudTextMuted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.tasks) { task ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = HudSurface),
                            border = BorderStroke(1.dp, if (task.isCompleted) HudSuccessGreen.copy(alpha = 0.4f) else HudPrimaryAmber.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(text = task.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (task.isCompleted) "● ESTADO: COMPLETADA" else "○ ESTADO: PENDIENTE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (task.isCompleted) HudSuccessGreen else HudGoldAccent
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
