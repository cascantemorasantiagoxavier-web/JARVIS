package com.example.jarvis.ui.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.ui.theme.HudGoldAccent
import com.example.jarvis.ui.theme.HudPrimaryAmber
import com.example.jarvis.ui.theme.HudSurface
import com.example.jarvis.ui.theme.HudTextMuted

data class AppLauncherItem(
    val name: String,
    val packageName: String,
    val iconSymbol: String
)

val defaultApps = listOf(
    AppLauncherItem("YouTube", "com.google.android.youtube", "▶️"),
    AppLauncherItem("Google Maps", "com.google.android.apps.maps", "🗺️"),
    AppLauncherItem("Chrome", "com.android.chrome", "🌐"),
    AppLauncherItem("WhatsApp", "com.whatsapp", "💬"),
    AppLauncherItem("Spotify", "com.spotify.music", "🎵"),
    AppLauncherItem("Ajustes", "com.android.settings", "⚙️"),
    AppLauncherItem("Cámara", "com.android.camera", "📷"),
    AppLauncherItem("Calculadora", "com.google.android.calculator", "🔢")
)

@Composable
fun AppLauncherPanel(
    modifier: Modifier = Modifier,
    onAppLaunched: ((String) -> Unit)? = null
) {
    HudPanel(title = "APLICACIONES", modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            defaultApps.take(6).forEach { app ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = HudSurface),
                    border = BorderStroke(0.8.dp, HudPrimaryAmber.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clickable {
                            val appMgr = AppContainer.appManager
                            if (appMgr != null && appMgr.isAppInstalled(app.packageName)) {
                                appMgr.launchApp(app.packageName)
                                onAppLaunched?.invoke("Abriendo ${app.name}")
                            } else {
                                onAppLaunched?.invoke("${app.name} no está instalada")
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(text = app.iconSymbol, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = app.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
