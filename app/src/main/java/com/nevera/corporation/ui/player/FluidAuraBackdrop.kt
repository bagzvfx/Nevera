package com.nevera.corporation.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.nevera.corporation.playback.SpectrumAudioBus
import com.nevera.corporation.playback.SyncedAudioSample
import com.nevera.corporation.ui.theme.NeveraBlack
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val FLUID_AURA_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float uBass;
uniform float uTreble;
uniform float4 colorPrimary;
uniform float4 colorSecondary;

half4 main(float2 fragCoord) {
    float2 resSize = max(iResolution, float2(1.0, 1.0));
    float2 uv = fragCoord / resSize;
    float wave1 = sin(uv.x * 3.1 + iTime * 0.45) * cos(uv.y * 2.4 - iTime * 0.35);
    float wave2 = cos(uv.x * 2.2 - iTime * 0.30 + uv.y * 2.7);
    float bassGain = 0.40 + uBass * 0.36;
    float trebleGain = 0.22 + uTreble * 0.32;
    float blend1 = clamp(0.42 + 0.30 * wave1 - uv.y * 0.34, 0.0, 1.0);
    float blend2 = clamp(0.28 + 0.25 * wave2 - (1.0 - uv.y) * 0.18 - uv.y * 0.24, 0.0, 1.0);
    float4 base = float4(0.0, 0.0, 0.0, 1.0);
    float4 res = mix(base, colorPrimary, blend1 * bassGain);
    res = mix(res, colorSecondary, blend2 * trebleGain);
    float trebleSpark = smoothstep(0.65, 0.0, length(uv - float2(0.76, 0.24))) * (uTreble * 0.16);
    res.rgb += colorSecondary.rgb * trebleSpark;
    float vignette = smoothstep(1.05, 0.20, uv.y);
    float4 finalColor = mix(base, res, vignette);
    return half4(finalColor);
}
"""

/**
 * Crash-safe hardware-accelerated AGSL GPU Shader aura on Android 13+ (API 33+)
 * reacting cleanly in brightness/luminance to real-time Bass and Treble audio energy,
 * with smooth animated Canvas fallback on Android < 13.
 */
@Composable
fun FluidAuraBackdrop(
    primaryColor: Color,
    secondaryColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember {
            runCatching { RuntimeShader(FLUID_AURA_AGSL) }.getOrNull()
        }
        if (shader != null) {
            AgslAuraCanvas(
                shader = shader,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                isPlaying = isPlaying,
                modifier = modifier
            )
            return
        }
    }

    FallbackAuraCanvas(
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        isPlaying = isPlaying,
        modifier = modifier
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslAuraCanvas(
    shader: RuntimeShader,
    primaryColor: Color,
    secondaryColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val brush = remember(shader) { ShaderBrush(shader) }
    val timeState = remember { mutableFloatStateOf(0.5f) }
    val bassSmooth = remember { mutableFloatStateOf(0f) }
    val trebleSmooth = remember { mutableFloatStateOf(0f) }
    val audioSample = remember { SyncedAudioSample() }

    LaunchedEffect(isPlaying) {
        var lastNanos = 0L
        while (isPlaying || bassSmooth.floatValue > 0.01f || trebleSmooth.floatValue > 0.01f) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos != 0L) {
                    ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                } else 0.016f
                lastNanos = nanos

                if (isPlaying) {
                    timeState.floatValue += dt
                    SpectrumAudioBus.sampleAt(System.nanoTime(), audioSample)
                }
                val targetBass = if (isPlaying && audioSample.isActive) audioSample.bassEnergy else 0f
                val targetTreble = if (isPlaying && audioSample.isActive) {
                    (audioSample.trebleEnergy * 0.7f + audioSample.vocalEnergy * 0.3f)
                } else 0f

                val att = 1f - exp(-22f * dt)
                val dec = 1f - exp(-7f * dt)
                bassSmooth.floatValue += (targetBass - bassSmooth.floatValue) *
                    (if (targetBass > bassSmooth.floatValue) att else dec)
                trebleSmooth.floatValue += (targetTreble - trebleSmooth.floatValue) *
                    (if (targetTreble > trebleSmooth.floatValue) att else dec)
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val bass = bassSmooth.floatValue.coerceIn(0f, 1f)
        val treble = trebleSmooth.floatValue.coerceIn(0f, 1f)
        val pulseBoost = 1f + bass * 0.24f
        shader.setFloatUniform("iResolution", w, h)
        shader.setFloatUniform("iTime", timeState.floatValue)
        shader.setFloatUniform("uBass", bass)
        shader.setFloatUniform("uTreble", treble)
        shader.setFloatUniform(
            "colorPrimary",
            (primaryColor.red * pulseBoost).coerceIn(0f, 1f),
            (primaryColor.green * pulseBoost).coerceIn(0f, 1f),
            (primaryColor.blue * pulseBoost).coerceIn(0f, 1f),
            1f
        )
        shader.setFloatUniform(
            "colorSecondary",
            (secondaryColor.red * (1f + treble * 0.20f)).coerceIn(0f, 1f),
            (secondaryColor.green * (1f + treble * 0.20f)).coerceIn(0f, 1f),
            (secondaryColor.blue * (1f + treble * 0.20f)).coerceIn(0f, 1f),
            1f
        )
        drawRect(brush = brush)
    }
}

@Composable
private fun FallbackAuraCanvas(
    primaryColor: Color,
    secondaryColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val phase = remember { mutableFloatStateOf(0f) }
    val bassSmooth = remember { mutableFloatStateOf(0f) }
    val trebleSmooth = remember { mutableFloatStateOf(0f) }
    val audioSample = remember { SyncedAudioSample() }

    LaunchedEffect(isPlaying) {
        var lastNanos = 0L
        while (isPlaying || bassSmooth.floatValue > 0.01f || trebleSmooth.floatValue > 0.01f) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos != 0L) {
                    ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                } else 0.016f
                lastNanos = nanos

                if (isPlaying) {
                    phase.floatValue += dt * 0.45f
                    SpectrumAudioBus.sampleAt(System.nanoTime(), audioSample)
                }
                val targetBass = if (isPlaying && audioSample.isActive) audioSample.bassEnergy else 0f
                val targetTreble = if (isPlaying && audioSample.isActive) audioSample.trebleEnergy else 0f
                val att = 1f - exp(-22f * dt)
                val dec = 1f - exp(-7f * dt)
                bassSmooth.floatValue += (targetBass - bassSmooth.floatValue) *
                    (if (targetBass > bassSmooth.floatValue) att else dec)
                trebleSmooth.floatValue += (targetTreble - trebleSmooth.floatValue) *
                    (if (targetTreble > trebleSmooth.floatValue) att else dec)
            }
        }
    }

    val baseBg = remember(primaryColor, secondaryColor) {
        Brush.verticalGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.28f),
                secondaryColor.copy(alpha = 0.14f),
                NeveraBlack
            )
        )
    }

    Box(modifier = modifier.fillMaxSize().background(baseBg)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val p = phase.floatValue
            val bass = bassSmooth.floatValue.coerceIn(0f, 1f)
            val treble = trebleSmooth.floatValue.coerceIn(0f, 1f)

            val c1 = Offset(w * (0.28f + 0.10f * cos(p)), h * (0.28f + 0.08f * sin(p)))
            val r1 = w * (0.72f + bass * 0.18f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.20f + bass * 0.22f), Color.Transparent),
                    center = c1,
                    radius = r1
                ),
                center = c1,
                radius = r1
            )

            val c2 = Offset(w * (0.74f + 0.09f * sin(p * 0.8f)), h * (0.42f + 0.09f * cos(p * 0.8f)))
            val r2 = w * (0.64f + treble * 0.16f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondaryColor.copy(alpha = 0.16f + treble * 0.20f), Color.Transparent),
                    center = c2,
                    radius = r2
                ),
                center = c2,
                radius = r2
            )
        }
    }
}
