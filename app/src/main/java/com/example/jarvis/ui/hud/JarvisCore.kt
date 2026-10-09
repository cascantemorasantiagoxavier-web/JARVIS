package com.example.jarvis.ui.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.example.jarvis.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JarvisCore(
    state: AssistantState,
    modifier: Modifier = Modifier,
    size: Int = 340,
    audioLevel: Float = 0f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_master_sphere_anim")

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == AssistantState.LISTENING || state == AssistantState.SPEAKING) 350 else 1200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val primaryGold = when (state) {
        AssistantState.IDLE -> HudGoldAccent
        AssistantState.LISTENING -> HudPrimaryAmber
        AssistantState.THINKING -> HudOrangeGlow
        AssistantState.EXECUTING -> HudSuccessGreen
        AssistantState.SPEAKING -> HudGoldAccent
        AssistantState.ERROR -> HudErrorRed
    }

    Box(modifier = modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size.dp)) {
            val center = Offset(size.dp.toPx() / 2f, size.dp.toPx() / 2f)
            val baseRadius = (size.dp.toPx() / 2f) * 0.96f
            val audioBoost = if (state == AssistantState.LISTENING) (audioLevel * 0.25f) else 0f
            val radius = baseRadius * (1f + audioBoost) * pulse

            // LAYER 1: Deep Volumetric Golden Ambient Plasma Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGold.copy(alpha = 0.7f),
                        HudPrimaryAmber.copy(alpha = 0.35f),
                        HudOrangeGlow.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 1.25f
                ),
                radius = radius * 1.25f,
                center = center
            )

            // LAYER 2: 3D Spherical Longitude & Latitude Grid (Dense Wireframe)
            for (i in 1..7) {
                val scaleY = i * 0.14f
                val h = radius * scaleY
                drawOval(
                    color = primaryGold.copy(alpha = 0.28f),
                    topLeft = Offset(center.x - radius, center.y - h),
                    size = Size(radius * 2f, h * 2f),
                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f))
                )
            }

            // LAYER 3: Multiple Inclined 3D Orbital Rings (0°, 35°, -50°, 70°)
            val angles = listOf(0f, 35f, -50f, 70f)
            angles.forEachIndexed { idx, angle ->
                withTransform({
                    translate(center.x, center.y)
                    rotate(angle)
                    translate(-center.x, -center.y)
                }) {
                    val h = radius * (0.3f + idx * 0.15f)
                    drawOval(
                        color = if (idx % 2 == 0) primaryGold.copy(alpha = 0.85f) else HudOrangeGlow.copy(alpha = 0.75f),
                        topLeft = Offset(center.x - radius * (0.95f + idx * 0.05f), center.y - h),
                        size = Size(radius * (1.9f + idx * 0.1f), h * 2f),
                        style = Stroke(
                            width = (2.5f - idx * 0.3f).dp.toPx(),
                            pathEffect = if (idx % 2 == 1) PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f) else null
                        )
                    )
                }
            }

            // LAYER 4: Dense Circuit Texture Nodes (120 intricate golden points mimicking reference photo)
            for (p in 0..119) {
                val angleP = (p * Math.PI / 60)
                val distFactor = 0.2f + ((p * 17) % 75) / 100f
                val pRadius = radius * distFactor
                val px = center.x + (pRadius * cos(angleP + (p * 0.15))).toFloat()
                val py = center.y + (pRadius * sin(angleP * 0.9) * 0.65f).toFloat()

                val alphaVal = 0.3f + ((p * 7) % 6) * 0.1f
                drawCircle(
                    color = when (p % 4) {
                        0 -> HudGoldAccent.copy(alpha = alphaVal)
                        1 -> HudPrimaryAmber.copy(alpha = alphaVal)
                        2 -> HudOrangeGlow.copy(alpha = alphaVal)
                        else -> HudCyanNeon.copy(alpha = alphaVal * 0.6f)
                    },
                    radius = (1.5f + (p % 3)).dp.toPx(),
                    center = Offset(px, py)
                )
            }

            // LAYER 5: Outer 96-Segment Circuit Perimeter Ring
            for (i in 0..95) {
                val angle = (i * Math.PI / 48)
                val isMajor = i % 12 == 0
                val lengthFactor = if (isMajor) 0.15f else 0.05f
                val startX = center.x + (radius * (1f - lengthFactor) * cos(angle)).toFloat()
                val startY = center.y + (radius * (1f - lengthFactor) * sin(angle)).toFloat()
                val endX = center.x + (radius * cos(angle)).toFloat()
                val endY = center.y + (radius * sin(angle)).toFloat()

                drawLine(
                    color = primaryGold.copy(alpha = if (isMajor) 0.95f else 0.35f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
                )
            }

            // LAYER 6: Inner Luminous Plasma Core & Radial Energy Burst
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        HudGoldAccent,
                        HudPrimaryAmber,
                        HudOrangeGlow.copy(alpha = 0.7f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 0.35f
                ),
                radius = radius * 0.35f,
                center = center
            )

            // LAYER 7: Center Core Reactor Point
            drawCircle(
                color = Color.White,
                radius = radius * 0.09f,
                center = center
            )
        }
    }
}
