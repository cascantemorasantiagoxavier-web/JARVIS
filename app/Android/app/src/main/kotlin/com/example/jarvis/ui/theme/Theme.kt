package com.example.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.jarvis.R
import com.example.jarvis.data.settings.CoreAccent

/** Paleta HUD. Valores muestreados del diseño de referencia (ámbar/naranja sobre negro). */
@Immutable
data class HudPalette(
    val background: Color = Color(0xFF020408),
    val panel: Color = Color(0xFF0B0603),
    val panelAlt: Color = Color(0xFF07111F), // azul oscuro para paneles secundarios
    val primary: Color = Color(0xFFFF8C00),
    val bright: Color = Color(0xFFFFB300),
    val deep: Color = Color(0xFFD85800),
    val glow: Color = Color(0xFFFF6A00),
    val gold: Color = Color(0xFFF8B048),
    val line: Color = Color(0x99FF8C00),
    val text: Color = Color(0xFFF8F0E0),
    val textDim: Color = Color(0xFFB8A894),
    val textFaint: Color = Color(0xFF7A6C5E),
    val ok: Color = Color(0xFF4CD964),
    val warn: Color = Color(0xFFFFC94D),
    val error: Color = Color(0xFFFF3D3D),
    val cyan: Color = Color(0xFF00E5FF),
)

val AmberPalette = HudPalette()

val CyanPalette = HudPalette(
    primary = Color(0xFF00E5FF),
    bright = Color(0xFF7DF3FF),
    deep = Color(0xFF0091A8),
    glow = Color(0xFF00B8D9),
    gold = Color(0xFFFFB300),
    line = Color(0x9900E5FF),
)

val LocalHud = staticCompositionLocalOf { AmberPalette }

val Orbitron = FontFamily(
    Font(R.font.orbitron_regular, FontWeight.Normal),
    Font(R.font.orbitron_medium, FontWeight.Medium),
    Font(R.font.orbitron_bold, FontWeight.Bold),
)

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_regular, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

object HudText {
    val brand = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Normal, fontSize = 28.sp, letterSpacing = 6.sp)
    val clock = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Medium, fontSize = 34.sp, letterSpacing = 2.sp)
    val title = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.8.sp)
    val label = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Normal, fontSize = 10.sp, letterSpacing = 1.4.sp)
    val body = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp)
    val bodySmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    val strong = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp)
}

@Composable
fun JarvisTheme(accent: CoreAccent = CoreAccent.AMBER, content: @Composable () -> Unit) {
    val p = if (accent == CoreAccent.CYAN) CyanPalette else AmberPalette
    val scheme = darkColorScheme(
        primary = p.primary,
        onPrimary = Color.Black,
        secondary = p.bright,
        background = p.background,
        onBackground = p.text,
        surface = p.panel,
        onSurface = p.text,
        surfaceVariant = p.panelAlt,
        onSurfaceVariant = p.textDim,
        error = p.error,
        outline = p.line,
    )
    val typography = Typography(
        bodyLarge = HudText.body,
        bodyMedium = HudText.body,
        bodySmall = HudText.bodySmall,
        labelLarge = HudText.title,
        labelMedium = HudText.label,
        titleLarge = HudText.title.copy(fontSize = 16.sp),
        titleMedium = HudText.title,
        headlineSmall = HudText.title.copy(fontSize = 18.sp),
    )
    CompositionLocalProvider(LocalHud provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
