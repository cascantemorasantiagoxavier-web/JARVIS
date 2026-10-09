package com.example.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SystemStatusPanel(modifier: Modifier = Modifier) {
    HudPanel(title = "ESTADO DEL SISTEMA", modifier = modifier) {
        StatusItem(label = "SISTEMA", value = "EN LÍNEA", active = true)
        StatusItem(label = "MEMORIA", value = "LISTA", active = true)
        StatusItem(label = "RED", value = "CONECTADO", active = true)
        StatusItem(label = "AGENTES", value = "ACTIVOS", active = true)
        StatusItem(label = "SEGURIDAD", value = "ACTIVA", active = true)
        StatusItem(label = "BATERÍA", value = "OPTIMIZADA", active = true)
    }
}

@Composable
fun StatusItem(label: String, value: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A99AD))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (active) Color(0xFF00E5FF) else Color(0xFFFF3D00)
        )
    }
}

@Composable
fun HudPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(Color(0xFF04070D), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF00E5FF)
            )
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}
