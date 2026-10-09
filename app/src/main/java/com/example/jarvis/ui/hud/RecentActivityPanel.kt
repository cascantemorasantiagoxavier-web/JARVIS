package com.example.jarvis.ui.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.jarvis.ui.theme.HudGoldAccent
import com.example.jarvis.ui.theme.HudPrimaryAmber
import com.example.jarvis.ui.theme.HudTextMuted

data class ActivityLogEntry(
    val title: String,
    val detail: String,
    val time: String = "Ahora"
)

@Composable
fun RecentActivityPanel(
    modifier: Modifier = Modifier,
    activities: List<ActivityLogEntry> = emptyList()
) {
    val displayList = if (activities.isNotEmpty()) {
        activities.take(5)
    } else {
        listOf(
            ActivityLogEntry("SISTEMA", "JARVIS listo y escuchando"),
            ActivityLogEntry("HERRAMIENTAS", "Hora y calculadora activas"),
            ActivityLogEntry("MEMORIA", "Vault de Obsidian en línea"),
            ActivityLogEntry("NUBE IA", "Realtime backend sincronizado")
        )
    }

    HudPanel(title = "ÚLTIMAS ACTIVIDADES", modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            displayList.forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = item.title, style = MaterialTheme.typography.labelSmall, color = HudGoldAccent)
                        Text(text = item.time, style = MaterialTheme.typography.labelSmall, color = HudTextMuted)
                    }
                    Text(text = item.detail, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}
