package com.nevera.corporation.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nevera.corporation.ui.theme.NeveraArtworkColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated fluid artwork mesh gradient backdrop with dual drifting organic color blobs.
 */
@Composable
fun ArtworkMeshBackdrop(
    colors: NeveraArtworkColors,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "mesh_drift")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mesh_phase"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.backdropTop, Color(0xFF0D0D10), Color(0xFF070709))
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val blob1Center = Offset(
                x = w * (0.28f + 0.16f * cos(phase)),
                y = h * (0.22f + 0.10f * sin(phase))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.28f), Color.Transparent),
                    center = blob1Center,
                    radius = w * 0.78f
                ),
                center = blob1Center,
                radius = w * 0.78f
            )

            val blob2Center = Offset(
                x = w * (0.74f + 0.14f * sin(phase * 0.8f)),
                y = h * (0.46f + 0.12f * cos(phase * 0.8f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.secondary.copy(alpha = 0.24f), Color.Transparent),
                    center = blob2Center,
                    radius = w * 0.70f
                ),
                center = blob2Center,
                radius = w * 0.70f
            )
        }
    }
}
