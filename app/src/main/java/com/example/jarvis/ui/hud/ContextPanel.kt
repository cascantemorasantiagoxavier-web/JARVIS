package com.example.jarvis.ui.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ContextPanel(modifier: Modifier = Modifier) {
    HudPanel(title = "CONTEXTO ACTIVO", modifier = modifier) {
        ContextItem(label = "PROYECTOS", detail = "Vault de Obsidian")
        ContextItem(label = "TAREAS", detail = "Lista local activa")
        ContextItem(label = "CALENDARIO", detail = "Sincronizado")
        ContextItem(label = "MEMORIA", detail = "Persistente activa")
        ContextItem(label = "CONOCIMIENTO", detail = "Obsidian conectado")
    }
}

@Composable
fun ContextItem(label: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF00E5FF))
        Text(text = detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A99AD))
    }
}
