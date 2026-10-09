package com.example.jarvis.features.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MemoryScreen(viewModel: MemoryViewModel = remember { MemoryViewModel() }) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Memoria y Conocimiento de JARVIS", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                MemoryCard(title = "Memoria de Trabajo", description = state.workingMemoryStatus)
            }
            item {
                MemoryCard(title = "Memoria de Sesión", description = state.sessionMemoryStatus)
            }
            item {
                MemoryCard(title = "Memoria a Largo Plazo", description = state.longTermMemoryStatus)
            }
            item {
                MemoryCard(title = "Conocimiento y Puente Obsidian", description = state.knowledgeStatus)
            }
        }
    }
}

@Composable
fun MemoryCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
