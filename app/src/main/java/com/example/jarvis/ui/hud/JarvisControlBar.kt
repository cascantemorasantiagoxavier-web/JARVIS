package com.example.jarvis.ui.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.jarvis.ui.theme.*

@Composable
fun JarvisControlBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    val controls = listOf(
        Triple("INICIO", "home", "⚡"),
        Triple("CHAT", "chat", "💬"),
        Triple("VOZ", "voice", "🎙️"),
        Triple("SHORTS", "shorts", "🎬"),
        Triple("TAREAS", "tasks", "📋"),
        Triple("AJUST", "settings", "⚙️")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurfaceDark)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        controls.forEach { (label, route, icon) ->
            val selected = currentRoute == route
            OutlinedButton(
                onClick = { onNavigate(route) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) HudCyanNeon.copy(alpha = 0.25f) else HudSurface,
                    contentColor = if (selected) HudCyanNeon else HudTextMuted
                ),
                border = BorderStroke(1.dp, if (selected) HudCyanNeon else HudCyanNeon.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .height(36.dp)
                    .weight(1f)
                    .padding(horizontal = 1.dp),
                contentPadding = PaddingValues(horizontal = 1.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "$icon $label",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}
