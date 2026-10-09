package com.example.jarvis.features.projects

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jarvis.ui.theme.*

@Composable
fun JarvisProjectsScreen(
    viewModel: ProjectsViewModel = viewModel()
) {
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
                Text(text = "📁 PROYECTOS Y OBSIDIAN VAULT", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                Text(text = "ARCHIVOS: ${state.files.size}", style = MaterialTheme.typography.labelSmall, color = HudGoldAccent)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (state.vaultPath.isNotBlank()) "Ruta: ${state.vaultPath}" else "Ruta: NO SELECCIONADA",
                style = MaterialTheme.typography.bodySmall,
                color = HudTextMuted
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (state.files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "NO DISPONIBLE / VAULT NO SELECCIONADO", style = MaterialTheme.typography.bodyMedium, color = HudErrorRed)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.files) { file ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = HudSurface),
                            border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = file,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
