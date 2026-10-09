package com.example.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.jarvis.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JarvisTopBar(state: AssistantState) {
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val localeEs = Locale.Builder().setLanguage("es").setRegion("ES").build()
        while (true) {
            val timeSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val dateSdf = SimpleDateFormat("EEE, d MMM yyyy", localeEs)
            val now = Date()
            currentTime = timeSdf.format(now)
            currentDate = dateSdf.format(now).uppercase()
            delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurfaceDark)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.2f)) {
            Text(
                text = "J.A.R.V.I.S.",
                style = MaterialTheme.typography.titleMedium,
                color = HudCyanNeon
            )
            Text(
                text = "\"Siempre a tu lado, jefe.\"",
                style = MaterialTheme.typography.labelSmall,
                color = HudGoldAccent
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = currentTime,
                style = MaterialTheme.typography.titleMedium,
                color = HudCyanNeon
            )
            Text(
                text = currentDate,
                style = MaterialTheme.typography.labelSmall,
                color = HudTextMuted
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            HudStatusIndicator(label = "SYS", value = "ONLINE")
            HudStatusIndicator(label = "ESTADO", value = state.name)
        }
    }
}

@Composable
fun HudStatusIndicator(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = HudTextMuted)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = HudGoldAccent)
    }
}
