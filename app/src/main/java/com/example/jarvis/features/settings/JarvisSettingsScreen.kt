package com.example.jarvis.features.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.jarvis.ui.theme.*

@Composable
fun JarvisSettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.onVaultUriSelected(it) }
    }

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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "⚙️ CONFIGURACIÓN DE JARVIS", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                Text(text = "STARK TECH", style = MaterialTheme.typography.labelSmall, color = HudGoldAccent)
            }
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Permissions Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = HudSurface),
                        border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "PERMISOS DE JARVIS", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                            Spacer(modifier = Modifier.height(12.dp))

                            PermissionStatusRow("🎤 Micrófono", state.isMicrophoneGranted)
                            PermissionStatusRow("🔔 Notificaciones", state.isNotificationGranted)
                            PermissionStatusRow("📅 Calendario", state.isCalendarGranted)
                            PermissionStatusRow("📇 Contactos", state.isContactsGranted)
                            PermissionStatusRow("📍 Ubicación", state.isLocationGranted)

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { activity?.let { viewModel.requestMissingPermissions(it) } },
                                    colors = ButtonDefaults.buttonColors(containerColor = HudCyanNeon),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Conceder Permisos", color = Color.Black)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.openAppSettings() },
                                    border = BorderStroke(1.dp, HudGoldAccent),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Ajustes Android", color = HudGoldAccent)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.sendTestNotification() },
                                border = BorderStroke(1.dp, HudCyanNeon),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🔔 Enviar Notificación de Prueba", color = HudCyanNeon)
                            }
                        }
                    }
                }

                // Diagnostic Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = HudSurface),
                        border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "DIAGNÓSTICO DEL SISTEMA", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                                Button(
                                    onClick = { viewModel.runDiagnostics() },
                                    enabled = !state.isTestingDiagnostics,
                                    colors = ButtonDefaults.buttonColors(containerColor = HudCyanNeon),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(if (state.isTestingDiagnostics) "Testeando..." else "Probar", color = Color.Black)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            DiagnosticItem("Backend local", state.diagnostics.backend)
                            DiagnosticItem("Gemini API", state.diagnostics.gemini)
                            DiagnosticItem("Micrófono (Permiso)", state.diagnostics.microphonePermission)
                            DiagnosticItem("Wake Word Service", state.diagnostics.wakeWordService)
                            DiagnosticItem("Wake Word Detectado", state.diagnostics.wakeWordDetected)
                            DiagnosticItem("Internet", state.diagnostics.internet)
                            DiagnosticItem("Memoria persistente", state.diagnostics.memory)
                            DiagnosticItem("Sintetizador TTS", state.diagnostics.ttsInitialized)
                            DiagnosticItem("OpenAI Realtime (${state.diagnostics.openAiRealtime})", state.diagnostics.openAiRealtime == "OK")
                        }
                    }
                }

                item { HudSettingItem(title = "Nombre del Asistente", value = state.assistantName) }

                // Obsidian Vault Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = HudSurface),
                        border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "Obsidian Vault", style = MaterialTheme.typography.titleMedium, color = HudCyanNeon)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "Estado: ${state.vaultStatus}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "Ruta: ${state.vaultPath}", style = MaterialTheme.typography.bodySmall, color = HudTextMuted)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { launcher.launch(null) },
                                    colors = ButtonDefaults.buttonColors(containerColor = HudCyanNeon),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Seleccionar Vault", color = Color.Black)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.validateVaultAccess() },
                                    border = BorderStroke(1.dp, HudGoldAccent),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Validar Acceso", color = HudGoldAccent)
                                }
                            }
                        }
                    }
                }

                item { HudSettingItem(title = "Voz", value = state.voiceConfig) }
                item { HudSettingItem(title = "Memoria", value = state.memoryConfig) }
                item { HudSettingItem(title = "IA", value = state.aiConfig) }
                item { HudSettingItem(title = "Seguridad", value = state.securityConfig) }
            }
        }
    }
}

@Composable
fun PermissionStatusRow(label: String, isGranted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text(
            text = if (isGranted) "● PERMITIDO" else "○ DENEGADO",
            style = MaterialTheme.typography.labelMedium,
            color = if (isGranted) HudSuccessGreen else HudErrorRed
        )
    }
}

@Composable
fun DiagnosticItem(label: String, isActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text(
            text = if (isActive) "● ACTIVO" else "○ NO DISPONIBLE / INACTIVO",
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) HudSuccessGreen else HudErrorRed
        )
    }
}

@Composable
fun HudSettingItem(title: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HudSurface),
        border = BorderStroke(1.dp, HudCyanNeon.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = HudCyanNeon)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = HudTextMuted)
        }
    }
}
