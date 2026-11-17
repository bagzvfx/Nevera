package com.nevera.corporation.ui.vfx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import com.nevera.corporation.data.model.HarmonicVfxMode
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Critically-damped spring physics solver for smooth tactile motion.
 */
class HarmonicSpring(
    var x: Float = 0f,
    var k: Float = 280f,
    var d: Float = 26f
) {
    var v: Float = 0f
    var to: Float = x

    fun step(dt: Float): Float {
        if (dt <= 0f) return x
        val steps = kotlin.math.ceil(dt / 0.008f).toInt().coerceAtLeast(1)
        val h = dt / steps
        repeat(steps) {
            v += (-k * (x - to) - d * v) * h
            x += v * h
        }
        return x
    }
}

internal fun harmonicEaseOut(t: Float): Float {
    val u = 1f - t.coerceIn(0f, 1f)
    return 1f - u * u * u
}

sealed class HarmonicParticle(val lifeSec: Float) {
    var elapsedSec: Float = 0f
    val progress: Float get() = (elapsedSec / lifeSec).coerceIn(0f, 1f)
    abstract fun DrawScope.draw(accent: Color)
}

/**
 * Zero-allocation concentric acoustic soundwave rings + soft halo.
 */
class AcousticRippleParticle(
    private val targetPos: Offset,
    private val maxRadius: Float = 135f,
    private val delaySec: Float = 0f,
    private val activeDurationSec: Float = 0.60f
) : HarmonicParticle(activeDurationSec + delaySec) {

    override fun DrawScope.draw(accent: Color) {
        val activeT = elapsedSec - delaySec
        if (activeT < 0f) return
        val k = (activeT / activeDurationSec).coerceIn(0f, 1f)
        val eased = harmonicEaseOut(k)
        val alpha = (1f - k) * (1f - k) * 0.56f
        val radius = 18f + eased * maxRadius

        drawCircle(
            color = accent.copy(alpha = alpha * 0.14f),
            radius = radius,
            center = this@AcousticRippleParticle.targetPos
        )
        drawCircle(
            color = accent.copy(alpha = alpha),
            radius = radius,
            center = this@AcousticRippleParticle.targetPos,
            style = Stroke(width = (3.0f * (1f - k * 0.65f)).coerceAtLeast(1.0f))
        )
    }
}

/**
 * Zero-allocation luminous bokeh motes floating smoothly upward when liking or saving a track.
 */
class BokehMoteParticle(
    private val origin: Offset,
    private val driftX: Float,
    private val riseY: Float,
    private val baseRadius: Float,
    lifeSec: Float = 0.80f
) : HarmonicParticle(lifeSec) {

    override fun DrawScope.draw(accent: Color) {
        val k = progress
        val eased = harmonicEaseOut(k)
        val alpha = sin(k * PI).toFloat() * 0.85f
        val pos = Offset(
            x = origin.x + driftX * eased,
            y = origin.y - riseY * eased
        )
        val r = baseRadius * (0.72f + 0.45f * sin(k * PI).toFloat())

        drawCircle(
            color = accent.copy(alpha = alpha * 0.28f),
            center = pos,
            radius = r * 1.9f
        )
        drawCircle(
            color = Color.White.copy(alpha = alpha * 0.88f),
            center = pos,
            radius = r * 0.72f
        )
    }
}

/**
 * Crisp zero-allocation fingertip touch glow + micro-ring anchored precisely at the touch point.
 */
class SpecularTouchParticle(
    private val targetPos: Offset,
    private val radius: Float = 68f,
    lifeSec: Float = 0.36f
) : HarmonicParticle(lifeSec) {
    override fun DrawScope.draw(accent: Color) {
        val k = progress
        val eased = harmonicEaseOut(k)
        val alpha = (1f - k) * (1f - k) * 0.44f
        val r = 14f + eased * radius
        drawCircle(
            color = accent.copy(alpha = alpha * 0.45f),
            center = this@SpecularTouchParticle.targetPos,
            radius = r * 0.85f
        )
        drawCircle(
            color = Color.White.copy(alpha = alpha * 0.90f),
            center = this@SpecularTouchParticle.targetPos,
            radius = r,
            style = Stroke(width = (2.3f * (1f - k * 0.6f)).coerceAtLeast(0.9f))
        )
        drawCircle(
            color = Color.White.copy(alpha = alpha * 0.58f),
            center = this@SpecularTouchParticle.targetPos,
            radius = (1f - k) * 16f + 4f
        )
    }
}

@Stable
class HarmonicFxController {
    internal val particles = mutableStateListOf<HarmonicParticle>()
    var mode by mutableStateOf(HarmonicVfxMode.FULL)
    var accentColor by mutableStateOf(Color(0xFFFFFFFF))

    @Volatile
    var overlayCoords: LayoutCoordinates? = null

    @Volatile
    var lastTouchLocalPos: Offset? = null
        private set

    @Volatile
    var lastTouchTimeNanos: Long = 0L
        private set

    private fun canEmit(): Boolean = mode == HarmonicVfxMode.FULL

    private fun enqueue(p: HarmonicParticle) {
        if (particles.size >= MAX_PARTICLES) {
            particles.removeAt(0)
        }
        particles.add(p)
    }

    /**
     * Records a pointer down in the overlay's exact local coordinate space and emits crisp fingertip glow.
     */
    fun recordPointerDown(localPos: Offset, nowNanos: Long = System.nanoTime()) {
        if (!localPos.x.isFinite() || !localPos.y.isFinite()) return
        lastTouchLocalPos = localPos
        lastTouchTimeNanos = nowNanos
        if (canEmit()) {
            enqueue(SpecularTouchParticle(localPos, radius = 68f))
        }
    }

    /**
     * Resolves an effect origin in the overlay's exact local coordinate space:
     * 1. Prefers the actual live fingertip coordinate if touched recently inside the target button's padded bounds.
     * 2. Falls back to `overlayCoords.localPositionOf(targetCoords, buttonCenter)` for non-touch / keyboard triggers.
     * Never falls back to `(0, 0)` or the screen center.
     */
    fun resolveEffectCenter(
        targetCoords: LayoutCoordinates?,
        nowNanos: Long = System.nanoTime(),
        maxTouchAgeNanos: Long = 450_000_000L
    ): Offset? {
        val host = overlayCoords?.takeIf { it.isAttached }
        val touchPos = lastTouchLocalPos
        val touchFresh = touchPos != null && (nowNanos - lastTouchTimeNanos) in 0L..maxTouchAgeNanos

        if (host != null && targetCoords != null && targetCoords.isAttached) {
            val topLeft = runCatching { host.localPositionOf(targetCoords, Offset.Zero) }.getOrNull()
            val bottomRight = runCatching {
                host.localPositionOf(
                    targetCoords,
                    Offset(targetCoords.size.width.toFloat(), targetCoords.size.height.toFloat())
                )
            }.getOrNull()

            if (topLeft != null && bottomRight != null) {
                val pad = 32f
                val minX = minOf(topLeft.x, bottomRight.x) - pad
                val maxX = maxOf(topLeft.x, bottomRight.x) + pad
                val minY = minOf(topLeft.y, bottomRight.y) - pad
                val maxY = maxOf(topLeft.y, bottomRight.y) + pad
                if (touchFresh && touchPos!!.x in minX..maxX && touchPos.y in minY..maxY) {
                    return touchPos
                }
                return Offset((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f)
            }
        }

        if (touchFresh) return touchPos
        return null
    }

    fun emitAcousticWave(center: Offset?) {
        if (!canEmit() || center == null || !center.x.isFinite() || !center.y.isFinite()) return
        enqueue(AcousticRippleParticle(center, maxRadius = 104f, delaySec = 0f))
        enqueue(AcousticRippleParticle(center, maxRadius = 138f, delaySec = 0.08f))
    }

    fun emitTouchGlow(center: Offset?) {
        if (!canEmit() || center == null || !center.x.isFinite() || !center.y.isFinite()) return
        enqueue(SpecularTouchParticle(center, radius = 68f))
    }

    fun emitBokehLove(center: Offset?) {
        if (!canEmit() || center == null || !center.x.isFinite() || !center.y.isFinite()) return
        enqueue(AcousticRippleParticle(center, maxRadius = 64f, delaySec = 0f, activeDurationSec = 0.46f))
        repeat(6) {
            enqueue(
                BokehMoteParticle(
                    origin = center + Offset(Random.nextFloat() * 22f - 11f, 0f),
                    driftX = Random.nextFloat() * 68f - 34f,
                    riseY = 48f + Random.nextFloat() * 48f,
                    baseRadius = 4.2f + Random.nextFloat() * 4.2f
                )
            )
        }
    }

    companion object {
        private const val MAX_PARTICLES = 24
    }
}

val LocalHarmonicFx = staticCompositionLocalOf { HarmonicFxController() }

/**
 * Pure 120fps lock-free Canvas rendering Harmonic effects in the parent Box's exact coordinate frame.
 * Holds zero pointerInput modifiers so underlying clicks and scrolls are never blocked or offset.
 */
@Composable
fun HarmonicFxOverlay(controller: HarmonicFxController = LocalHarmonicFx.current) {
    val frameTickState = remember { mutableLongStateOf(0L) }
    var hasActiveParticles by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        snapshotFlow { controller.particles.isNotEmpty() }
            .collectLatest { active ->
                hasActiveParticles = active
                if (!active) return@collectLatest
                var lastNanos = 0L
                while (controller.particles.isNotEmpty()) {
                    withFrameNanos { nanos ->
                        if (lastNanos != 0L) {
                            val dt = ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.034f)
                            var i = controller.particles.size - 1
                            while (i >= 0) {
                                val p = controller.particles[i]
                                p.elapsedSec += dt
                                if (p.elapsedSec >= p.lifeSec) {
                                    controller.particles.removeAt(i)
                                }
                                i--
                            }
                        }
                        lastNanos = nanos
                        frameTickState.longValue++
                    }
                }
                hasActiveParticles = false
            }
    }

    if (hasActiveParticles) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unusedTick = frameTickState.longValue
            val accent = controller.accentColor
            for (i in 0 until controller.particles.size) {
                val particle = controller.particles[i]
                with(particle) {
                    draw(accent)
                }
            }
        }
    }
}
