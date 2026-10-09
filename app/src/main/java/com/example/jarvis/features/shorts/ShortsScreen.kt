package com.example.jarvis.features.shorts

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
import com.example.jarvis.domain.shorts.ShortProject
import com.example.jarvis.domain.shorts.ShortStatus
import com.example.jarvis.ui.theme.*

@Composable
fun ShortsScreen(viewModel: ShortsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackground)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HudSurfaceDark, RoundedCornerShape(6.dp))
                    .border(1.dp, HudCyanNeon.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🎬 YOUTUBE SHORTS AUTOMATION", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                Text(text = "STARK CREATOR", style = MaterialTheme.typography.labelSmall, color = HudGoldAccent)
            }

            Spacer(modifier = Modifier.height(12.dp))

            state.statusMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = HudSurfaceDark),
                    border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudCyanNeon
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Create New Short Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = HudSurface),
                border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "CREAR NUEVO SHORT", style = MaterialTheme.typography.titleSmall, color = HudCyanNeon)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.topicInput,
                        onValueChange = { viewModel.onTopicChanged(it) },
                        label = { Text("Tema del Short (ej. Inteligencia Artificial)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HudCyanNeon,
                            unfocusedBorderColor = HudCyanNeon.copy(alpha = 0.3f),
                            focusedLabelColor = HudCyanNeon
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Duración: ${state.targetDuration}s", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.weight(1f))
                        FilterChip(
                            selected = state.targetDuration == 15,
                            onClick = { viewModel.onDurationChanged(15) },
                            label = { Text("15s") }
                        )
                        FilterChip(
                            selected = state.targetDuration == 30,
                            onClick = { viewModel.onDurationChanged(30) },
                            label = { Text("30s") }
                        )
                        FilterChip(
                            selected = state.targetDuration == 60,
                            onClick = { viewModel.onDurationChanged(60) },
                            label = { Text("60s") }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.createShortProject() },
                        enabled = !state.isCreating && state.topicInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = HudCyanNeon),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.isCreating) "Generando Guion..." else "🚀 Generar Guion y Metadata", color = Color.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "PROYECTOS DE SHORTS (${state.projects.size})", style = MaterialTheme.typography.titleSmall, color = HudGoldAccent)
            Spacer(modifier = Modifier.height(8.dp))

            if (state.projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No hay ningún Short creado aún.\nPídeme a JARVIS: 'Crea un Short sobre IA'",
                        color = HudTextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        ShortProjectCard(
                            project = project,
                            onRender = { viewModel.renderProject(project.id) },
                            onPublish = { viewModel.publishProject(project.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShortProjectCard(
    project: ShortProject,
    onRender: () -> Unit,
    onPublish: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HudSurface),
        border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = project.metadata?.title ?: project.topic,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
                StatusBadge(project.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            project.script?.let { script ->
                Text(text = "Hook: \"${script.hook}\"", style = MaterialTheme.typography.bodySmall, color = HudCyanNeon)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Cuerpo: ${script.mainBody}", style = MaterialTheme.typography.bodySmall, color = HudTextMuted)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (project.status == ShortStatus.SCRIPT_READY || project.status == ShortStatus.DRAFT) {
                    Button(
                        onClick = onRender,
                        colors = ButtonDefaults.buttonColors(containerColor = HudCyanNeon),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📹 Renderizar 1080x1920", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (project.status == ShortStatus.RENDERED || project.status == ShortStatus.READY_TO_PUBLISH) {
                    Button(
                        onClick = onPublish,
                        colors = ButtonDefaults.buttonColors(containerColor = HudGoldAccent),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🚀 Publicar en YouTube", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (project.status == ShortStatus.PUBLISHED) {
                    Text(
                        text = "✅ Publicado en YouTube (${project.youtubeVideoId})",
                        color = HudSuccessGreen,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: ShortStatus) {
    val (bgColor, textColor) = when (status) {
        ShortStatus.PUBLISHED -> HudSuccessGreen to Color.Black
        ShortStatus.RENDERED -> HudCyanNeon to Color.Black
        ShortStatus.SCRIPT_READY -> HudGoldAccent to Color.Black
        ShortStatus.RENDERING, ShortStatus.UPLOADING -> Color.Yellow to Color.Black
        ShortStatus.FAILED -> HudErrorRed to Color.White
        else -> HudSurfaceDark to Color.White
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = status.name,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}
