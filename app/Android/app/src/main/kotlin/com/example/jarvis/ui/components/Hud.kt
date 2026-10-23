package com.example.jarvis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlin.math.min

enum class Corner { TopLeft, TopRight, BottomRight, BottomLeft }

/** Forma con esquinas biseladas, como los paneles del diseño. */
class ChamferShape(private val cut: Dp, private val corners: Set<Corner> = setOf(Corner.TopLeft, Corner.BottomRight)) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(min(size.width, size.height) / 3f)
        val tl = if (Corner.TopLeft in corners) c else 0f
        val tr = if (Corner.TopRight in corners) c else 0f
        val br = if (Corner.BottomRight in corners) c else 0f
        val bl = if (Corner.BottomLeft in corners) c else 0f
        val p = Path().apply {
            moveTo(tl, 0f)
            lineTo(size.width - tr, 0f)
            lineTo(size.width, tr)
            lineTo(size.width, size.height - br)
            lineTo(size.width - br, size.height)
            lineTo(bl, size.height)
            lineTo(0f, size.height - bl)
            lineTo(0f, tl)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Fondo, borde luminoso y segmentos de acento de un panel HUD. */
fun Modifier.hudFrame(
    cut: Dp = 14.dp,
    corners: Set<Corner> = setOf(Corner.TopLeft, Corner.BottomRight),
    accent: Color,
    line: Color,
    fill: Color,
    glow: Boolean = true,
): Modifier {
    val shape = ChamferShape(cut, corners)
    return this
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            if (glow) drawOutline(outline, accent.copy(alpha = 0.10f), style = Stroke(width = 7.dp.toPx()))
            drawOutline(outline, Brush.verticalGradient(listOf(fill.copy(alpha = 0.92f), fill.copy(alpha = 0.70f))))
            drawOutline(outline, line, style = Stroke(width = 1.dp.toPx()))
            // Segmentos de acento (como las barras brillantes del diseño)
            val c = cut.toPx().coerceAtMost(min(size.width, size.height) / 3f)
            val w = 2.5.dp.toPx()
            val seg = min(size.width * 0.28f, 90.dp.toPx())
            drawLine(accent, Offset(c, 0f), Offset(c + seg, 0f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(accent, Offset(size.width - c - seg, size.height), Offset(size.width - c, size.height), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(accent, Offset(0f, c), Offset(c, 0f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(accent, Offset(size.width - c, size.height), Offset(size.width, size.height - c), strokeWidth = w, cap = StrokeCap.Round)
        }
}

@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    cut: Dp = 14.dp,
    corners: Set<Corner> = setOf(Corner.TopLeft, Corner.BottomRight),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hud = LocalHud.current
    var m = modifier.hudFrame(cut, corners, hud.primary, hud.line, hud.panel)
    if (onClick != null) m = m.clip(ChamferShape(cut, corners)).clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(contentPadding)) {
        if (title != null) {
            Text(title.uppercase(), style = HudText.title, color = hud.text)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(hud.line, Color.Transparent))))
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** Botón HUD con icono y etiqueta (acceso a módulos). */
@Composable
fun HudButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
) {
    val hud = LocalHud.current
    val accent = if (highlighted) hud.bright else hud.primary
    Column(
        modifier
            .semantics { contentDescription = label }
            .hudFrame(cut = 8.dp, corners = setOf(Corner.TopLeft, Corner.BottomRight), accent = accent.copy(alpha = if (enabled) 1f else 0.3f), line = hud.line, fill = hud.panel, glow = highlighted)
            .clip(ChamferShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) accent else hud.textFaint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label.uppercase(), style = HudText.label, color = if (enabled) hud.text else hud.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Botón de texto HUD compacto. */
@Composable
fun HudChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    val hud = LocalHud.current
    Row(
        modifier
            .clip(ChamferShape(6.dp))
            .background(if (selected) hud.primary.copy(alpha = 0.22f) else hud.panel)
            .border(1.dp, if (selected) hud.bright else hud.line, ChamferShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) hud.bright else hud.textFaint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = HudText.bodySmall, color = if (enabled) hud.text else hud.textFaint, maxLines = 1)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Etiqueta de estado de un dato (encontrado, verificado, no disponible…). */
@Composable
fun FieldStatusTag(status: FieldStatus, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    val (text, color) = when (status) {
        FieldStatus.FOUND -> "1 fuente" to hud.textDim
        FieldStatus.VERIFIED -> "verificado" to hud.ok
        FieldStatus.UNAVAILABLE -> "no disponible" to hud.textFaint
        FieldStatus.CONFLICT -> "contradictorio" to hud.error
        FieldStatus.APPROXIMATE -> "aproximado" to hud.warn
    }
    Text(text, style = HudText.label, color = color, modifier = modifier)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    Text(text.uppercase(), style = HudText.label, color = hud.bright, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
fun RowScope.Weighted(weight: Float, content: @Composable () -> Unit) {
    Box(Modifier.weight(weight)) { content() }
}
