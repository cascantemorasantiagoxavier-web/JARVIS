package com.example.jarvis.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.onVaultUriSelected(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Configuración de JARVIS", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SettingItem(title = "Nombre del Asistente", value = state.assistantName) }
            
            // Obsidian Vault Section
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Obsidian Vault", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Estado: ${state.vaultStatus}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "Ruta: ${state.vaultPath}", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { launcher.launch(null) }) {
                                Text("Seleccionar Vault")
                            }
                            OutlinedButton(onClick = { viewModel.validateVaultAccess() }) {
                                Text("Validar Acceso")
                            }
                        }
                    }
                }
            }

            item { SettingItem(title = "Voz", value = state.voiceConfig) }
            item { SettingItem(title = "Memoria", value = state.memoryConfig) }
            item { SettingItem(title = "IA", value = state.aiConfig) }
            item { SettingItem(title = "Seguridad", value = state.securityConfig) }
        }
    }
}

@Composable
fun SettingItem(title: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
