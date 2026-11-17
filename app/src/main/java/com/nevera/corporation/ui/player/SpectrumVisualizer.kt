package com.nevera.corporation.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nevera.corporation.data.model.VisualizerStyle
import com.nevera.corporation.playback.SpectrumAudioBus
import com.nevera.corporation.playback.SyncedAudioSample
import kotlin.math.exp

/**
 * 120fps Unified Equalizer Bar Visualizer synced to audible AudioTrack output:
 * - Single seamless visualizer container with clean spacing separating Bass (0..9),
 *   Vocal (10..21), and Treble (22..31) ranges.
 * - Strictly text-free (no zone labels and no BPM text badge).
 * - Zero Compose recompositions per frame.
 */
@Composable
fun VerticalEqualizerVisualizer(
    style: VisualizerStyle,
    isPlaying: Boolean,
    accentColor: Color,
    secondaryColor: Color,
    onToggleStyle: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (style == VisualizerStyle.OFF) return

    val barCount = 32
    val displayHeights = remember { FloatArray(barCount) { 0.04f } }
    val peakCaps = remember { FloatArray(barCount) { 0.04f } }
    val peakVelocities = remember { FloatArray(barCount) }
    val syncedSample = remember { SyncedAudioSample() }
    val frameTick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos != 0L) {
                    ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.034f)
                } else 0.016f
                lastNanos = nanos

                val sampleOk = if (isPlaying) {
                    SpectrumAudioBus.sampleAt(System.nanoTime(), syncedSample)
                } else false
                val audioActive = isPlaying && sampleOk && syncedSample.isActive
                val targetBands = syncedSample.bands

                val attackAlpha = 1f - exp(-30f * dt)
                val decayAlpha = 1f - exp(-11.5f * dt)

                var anyMoving = audioActive
                for (i in 0 until barCount) {
                    val target = if (audioActive) targetBands[i].coerceIn(0.03f, 1f) else 0.03f
                    val cur = displayHeights[i]
                    val alpha = if (target > cur) attackAlpha else decayAlpha
                    val nextH = cur + (target - cur) * alpha
                    displayHeights[i] = nextH

                    if (nextH >= peakCaps[i]) {
                        peakCaps[i] = nextH
                        peakVelocities[i] = 0f
                    } else {
                        peakVelocities[i] += 1.75f * dt
                        peakCaps[i] = (peakCaps[i] - peakVelocities[i] * dt).coerceAtLeast(nextH)
                    }
                    if (nextH > 0.035f || peakCaps[i] > 0.035f) anyMoving = true
                }

                if (anyMoving) {
                    frameTick.longValue++
                }
            }
        }
    }

    val barBrush = remember(accentColor, secondaryColor) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.96f),
                accentColor.copy(alpha = 0.92f),
                secondaryColor.copy(alpha = 0.72f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleStyle
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unusedTick = frameTick.longValue
            val totalW = size.width
            val totalH = size.height
            val slotW = totalW / barCount
            val barW = (slotW * 0.56f).coerceIn(3f, 10f)
            val corner = CornerRadius(barW / 2f, barW / 2f)

            for (i in 0 until barCount) {
                val norm = displayHeights[i].coerceIn(0.03f, 1f)
                val capNorm = peakCaps[i].coerceIn(norm, 1f)
                val x = i * slotW + (slotW - barW) / 2f

                if (style == VisualizerStyle.SYMMETRIC_BARS) {
                    val midY = totalH / 2f
                    val halfH = (norm * totalH * 0.46f).coerceAtLeast(barW * 0.55f)
                    drawRoundRect(
                        brush = barBrush,
                        topLeft = Offset(x, midY - halfH),
                        size = Size(barW, halfH * 2f),
                        cornerRadius = corner
                    )
                } else {
                    val barH = (norm * (totalH - 5f)).coerceAtLeast(barW)
                    val topY = totalH - barH
                    drawRoundRect(
                        brush = barBrush,
                        topLeft = Offset(x, topY),
                        size = Size(barW, barH),
                        cornerRadius = corner
                    )

                    val capY = (totalH - capNorm * (totalH - 5f) - 3f).coerceAtLeast(1.5f)
                    drawCircle(
                        color = Color.White.copy(alpha = (norm * 0.88f).coerceIn(0.18f, 0.92f)),
                        radius = barW * 0.36f,
                        center = Offset(x + barW / 2f, capY)
                    )
                }
            }
        }
    }
}
