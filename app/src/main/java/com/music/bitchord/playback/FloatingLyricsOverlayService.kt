package com.music.bitchord.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.NeveraVocalMotion
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Borderless, frameless floating karaoke lyrics overlay rendered directly above
 * other apps via [WindowManager] (requires [Settings.canDrawOverlays]).
 *
 * Uses the exact same [NeveraVocalMotion] smart vocal-sustain engine as playback,
 * a drift-steered VSync clock (zero 140ms stutter), clean glitch-free per-character
 * wave, warm vocal-hold & Reff glow, delicate floating spirit particles synced to
 * bass rhythm, automatic Romaji sub-line, drag to move, and double-tap to close.
 */
class FloatingLyricsOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var karaokeView: KaraokeOverlayView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            AppSettings.setFloatingLyricsOverlay(false)
            stopSelf()
            return
        }
        setupOverlayView()
        observeLyrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        karaokeView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        karaokeView = null
        windowManager = null
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun setupOverlayView() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val view = KaraokeOverlayView(this)
        karaokeView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            dp(128f),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(72f)
        }
        params = lp

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        var lastTapAt = 0L
        val dragSlop = dp(6f).toFloat()

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!moved && (abs(dx) > dragSlop || abs(dy) > dragSlop)) {
                        moved = true
                    }
                    if (moved) {
                        lp.x = initialX + dx.toInt()
                        lp.y = initialY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTapAt <= 320L) {
                            AppSettings.setFloatingLyricsOverlay(false)
                            stopSelf()
                        } else {
                            lastTapAt = now
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(view, lp)
            Toast.makeText(
                this,
                "Geser lirik untuk pindah · Ketuk 2x untuk tutup",
                Toast.LENGTH_SHORT,
            ).show()
        }.onFailure {
            AppSettings.setFloatingLyricsOverlay(false)
            stopSelf()
        }
    }

    private fun observeLyrics() {
        scope.launch {
            combine(
                NeveraAudioBus.overlaySnapshot,
                NeveraAudioBus.floatingLyricsEnabled,
            ) { snapshot, enabled ->
                snapshot to enabled
            }.collect { (snapshot, enabled) ->
                if (!enabled) {
                    stopSelf()
                    return@collect
                }
                karaokeView?.updateSnapshot(snapshot)
            }
        }
    }

    /**
     * Borderless, VSync-driven Canvas view with drift-steered clock,
     * [NeveraVocalMotion] smart vocal timing, and gentle floating spirit motes.
     */
    private class KaraokeOverlayView(context: Context) : View(context) {
        private var snapshot: OverlayLyricSnapshot = OverlayLyricSnapshot()
        private var lastKey: String = ""
        private var transitionStartMs: Long = 0L

        // Smooth steered display clock (prevents 140ms anchor jitter!)
        private var displayPosMs: Double = 0.0
        private var lastFrameElapsedMs: Long = 0L
        private var smoothBass: Float = 0f
        private var spiritTimeSec: Float = 0f

        private val density = context.resources.displayMetrics.density
        private val scaledDensity = context.resources.displayMetrics.scaledDensity

        private val jakartaBold: Typeface =
            runCatching { ResourcesCompat.getFont(context, R.font.jakarta_bold) }.getOrNull()
                ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        private val jakartaMedium: Typeface =
            runCatching { ResourcesCompat.getFont(context, R.font.jakarta_medium) }.getOrNull()
                ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaBold
            textSize = 20.5f * scaledDensity
            color = Color.argb(145, 255, 255, 255)
            setShadowLayer(6.5f * density, 0f, 1.5f * density, 0xCC000000.toInt())
        }

        private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaBold
            textSize = 20.5f * scaledDensity
            color = Color.WHITE
            setShadowLayer(5.5f * density, 0f, 1.2f * density, 0xBE000000.toInt())
        }

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaBold
            textSize = 20.5f * scaledDensity
            color = Color.argb(130, 255, 236, 212)
            maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
        }

        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaMedium
            textSize = 13.5f * scaledDensity
            color = Color.argb(228, 245, 241, 233)
            textAlign = Paint.Align.CENTER
            setShadowLayer(5f * density, 0f, 1.2f * density, 0xD0000000.toInt())
        }

        private val spiritPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fadePaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        private val waveLiftPx = 3.2f * density
        private val featherPx = 24f * density

        // 10 delicate spirit particles floating smoothly around the borderless lyrics
        private val spiritSeeds = FloatArray(10 * 3) { i ->
            val p = i / 3
            when (i % 3) {
                0 -> 0.14f + ((p * 29 + 7) % 72) / 100f
                1 -> ((p * 47 + 19) % 100) / 100f
                else -> 0.18f + ((p * 13) % 8) * 0.025f
            }
        }

        init {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun updateSnapshot(newSnapshot: OverlayLyricSnapshot) {
            val newKey = "${newSnapshot.line?.timeMs ?: -1L}:${newSnapshot.primaryText}"
            val now = SystemClock.elapsedRealtime()
            val expectedPos = if (newSnapshot.isPlaying && newSnapshot.anchorElapsedMs > 0L) {
                newSnapshot.anchorPositionMs + (now - newSnapshot.anchorElapsedMs).coerceIn(0L, 4_000L)
            } else {
                newSnapshot.anchorPositionMs
            }.toDouble()

            if (lastFrameElapsedMs == 0L || abs(expectedPos - displayPosMs) > 380.0 || !newSnapshot.isPlaying) {
                displayPosMs = expectedPos
            }
            if (newKey != lastKey) {
                lastKey = newKey
                transitionStartMs = now
            }
            snapshot = newSnapshot
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val nowElapsed = SystemClock.elapsedRealtime()
            val dtMs = if (lastFrameElapsedMs == 0L) 16L else (nowElapsed - lastFrameElapsedMs).coerceIn(4L, 48L)
            lastFrameElapsedMs = nowElapsed
            val dtSec = dtMs / 1000f

            // Steered VSync playback clock: buttery smooth 60/120fps without periodic anchor jumps
            if (snapshot.isPlaying) {
                displayPosMs += dtMs.toDouble()
                if (snapshot.anchorElapsedMs > 0L) {
                    val targetPos = snapshot.anchorPositionMs +
                        (nowElapsed - snapshot.anchorElapsedMs).coerceIn(0L, 4_000L)
                    val err = targetPos.toDouble() - displayPosMs
                    if (abs(err) > 420.0) {
                        displayPosMs = targetPos.toDouble()
                    } else {
                        displayPosMs += err * 0.14
                    }
                }
            } else {
                displayPosMs = snapshot.anchorPositionMs.toDouble()
            }

            // Smooth bass energy for spirit particles & Reff breathing
            val targetBass = if (snapshot.isPlaying) NeveraAudioBus.bassEnergy.value else 0f
            val bassK = 1f - exp((-dtSec / if (targetBass > smoothBass) 0.04f else 0.18f).toDouble()).toFloat()
            smoothBass += (targetBass - smoothBass) * bassK
            if (snapshot.isPlaying) {
                spiritTimeSec += dtSec * (1f + smoothBass * 0.65f)
            }

            val primary = snapshot.primaryText.ifBlank {
                if (snapshot.trackTitle.isNotBlank()) "♪ ${snapshot.trackTitle}" else "♪"
            }
            val entryT = ((nowElapsed - transitionStartMs).toFloat() / 240f).coerceIn(0f, 1f)
            val smoothEntry = entryT * entryT * (3f - 2f * entryT)
            val slideY = (1f - smoothEntry) * 8f * density

            // 1. Delicate floating spirit motes synced to bass & vocal energy
            if (snapshot.isPlaying) {
                val spiritVis = (0.30f + smoothBass * 0.55f + if (snapshot.isBestPart) 0.30f else 0f).coerceAtMost(1f)
                for (i in 0 until 10) {
                    val b = i * 3
                    val xNorm = spiritSeeds[b]
                    val phase = spiritSeeds[b + 1]
                    val speed = spiritSeeds[b + 2]
                    val p = (phase + spiritTimeSec * speed) % 1f
                    val px = xNorm * w + sin((spiritTimeSec * 1.4f + phase * 6.28f).toDouble()).toFloat() * 14f * density
                    val py = h * (0.86f - p * 0.72f)
                    val fade = sin((p * PI).toDouble()).toFloat()
                    val alpha = (fade * spiritVis * 150f).roundToInt().coerceIn(0, 175)
                    if (alpha > 4) {
                        val r = (2.8f + smoothBass * 2.2f) * density
                        val coreColor = if (snapshot.isBestPart) {
                            Color.argb(alpha, 255, 224, 178)
                        } else {
                            Color.argb(alpha, 255, 255, 255)
                        }
                        spiritPaint.shader = RadialGradient(
                            px, py, r * 2.4f,
                            coreColor, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                        )
                        canvas.drawCircle(px, py, r * 2.4f, spiritPaint)
                    }
                }
            }

            // 2. Auto-fit typography cleanly so long lines never clip
            val maxTextW = w - 26f * density
            val baseSize = 20.5f * scaledDensity
            val minSize = 14.5f * scaledDensity
            litPaint.textSize = baseSize
            val measuredW = litPaint.measureText(primary)
            val fitSize = if (measuredW > maxTextW && measuredW > 0f) {
                (baseSize * (maxTextW / measuredW)).coerceIn(minSize, baseSize)
            } else {
                baseSize
            }
            dimPaint.textSize = fitSize
            litPaint.textSize = fitSize
            glowPaint.textSize = fitSize

            val wrapped = wrapMaxTwoLines(primary, litPaint, maxTextW)
            val lineHeight = (litPaint.descent() - litPaint.ascent()) * 1.20f
            val firstBaseline = 28f * density - litPaint.ascent() + slideY

            val timing = snapshot.line
            val songPosMs = displayPosMs.toLong()
            val isBestPart = snapshot.isBestPart
            val canSweep = timing != null && timing.isWordSynced && !isRtlText(primary)
            val totalRevealed = if (canSweep && timing != null) {
                NeveraVocalMotion.smartRevealedChars(timing, songPosMs).coerceIn(0f, primary.length.toFloat())
            } else {
                primary.length.toFloat()
            }

            var charOffset = 0
            for (rowIdx in wrapped.indices) {
                val rowText = wrapped[rowIdx]
                val rowStart = primary.indexOf(rowText, charOffset).takeIf { it >= 0 } ?: charOffset
                charOffset = rowStart + rowText.length
                val rowW = litPaint.measureText(rowText)
                val startX = (w - rowW) * 0.5f
                val baseline = firstBaseline + rowIdx * lineHeight

                if (!canSweep || timing == null) {
                    litPaint.alpha = (255 * smoothEntry).roundToInt()
                    if (isBestPart) {
                        glowPaint.alpha = (110 * smoothEntry).roundToInt()
                        canvas.drawText(rowText, startX, baseline, glowPaint)
                    }
                    canvas.drawText(rowText, startX, baseline, litPaint)
                } else {
                    val rowRevealed = (totalRevealed - rowStart).coerceIn(0f, rowText.length.toFloat())
                    val sweepEdgeX = startX + measureAtChar(rowText, litPaint, rowRevealed)

                    dimPaint.alpha = (142 * smoothEntry).roundToInt()
                    drawSmartWavedRow(
                        canvas = canvas,
                        text = rowText,
                        rowStart = rowStart,
                        startX = startX,
                        baseline = baseline,
                        paint = dimPaint,
                        timing = timing,
                        songPosMs = songPosMs,
                        totalRevealed = totalRevealed,
                        drawGlow = false,
                        entryAlpha = smoothEntry,
                        isBestPart = isBestPart,
                    )

                    if (rowRevealed > 0.01f) {
                        litPaint.alpha = (255 * smoothEntry).roundToInt()
                        val topClip = baseline + litPaint.ascent() - waveLiftPx * 3.0f
                        val bottomClip = baseline + litPaint.descent() + 6f * density
                        if (rowRevealed >= rowText.length - 0.01f) {
                            drawSmartWavedRow(
                                canvas = canvas,
                                text = rowText,
                                rowStart = rowStart,
                                startX = startX,
                                baseline = baseline,
                                paint = litPaint,
                                timing = timing,
                                songPosMs = songPosMs,
                                totalRevealed = totalRevealed,
                                drawGlow = true,
                                entryAlpha = smoothEntry,
                                isBestPart = isBestPart,
                            )
                        } else {
                            val activeFeather = minOf(featherPx, max(4f, sweepEdgeX - startX))
                            val save = canvas.saveLayer(0f, topClip, w, bottomClip, null)
                            canvas.clipRect(0f, topClip, sweepEdgeX + activeFeather, bottomClip)
                            drawSmartWavedRow(
                                canvas = canvas,
                                text = rowText,
                                rowStart = rowStart,
                                startX = startX,
                                baseline = baseline,
                                paint = litPaint,
                                timing = timing,
                                songPosMs = songPosMs,
                                totalRevealed = totalRevealed,
                                drawGlow = true,
                                entryAlpha = smoothEntry,
                                isBestPart = isBestPart,
                            )
                            fadePaint.shader = LinearGradient(
                                sweepEdgeX, 0f, sweepEdgeX + activeFeather, 0f,
                                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                            )
                            canvas.drawRect(0f, topClip, w, bottomClip, fadePaint)
                            canvas.restoreToCount(save)
                        }
                    }
                }
            }

            // 3. Romaji / secondary sub-line underneath
            val sub = snapshot.secondaryText
            if (sub.isNotBlank() && sub != primary) {
                val subBaseline = firstBaseline + (wrapped.size - 1) * lineHeight +
                    litPaint.descent() + 6f * density - subPaint.ascent()
                subPaint.alpha = (224 * smoothEntry).roundToInt()
                canvas.drawText(ellipsizeText(sub, subPaint, maxTextW), w * 0.5f, subBaseline, subPaint)
            }

            if (snapshot.isPlaying || entryT < 1f || smoothBass > 0.01f) {
                postInvalidateOnAnimation()
            }
        }

        private fun drawSmartWavedRow(
            canvas: Canvas,
            text: String,
            rowStart: Int,
            startX: Float,
            baseline: Float,
            paint: Paint,
            timing: LyricLine,
            songPosMs: Long,
            totalRevealed: Float,
            drawGlow: Boolean,
            entryAlpha: Float,
            isBestPart: Boolean,
        ) {
            if (text.isEmpty()) return
            val allowWave = !NeveraVocalMotion.hasJoinedScript(text)
            if (!allowWave) {
                canvas.drawText(text, startX, baseline, paint)
                return
            }
            val spans = timing.wordSpans
            var x = startX
            for (i in text.indices) {
                val globalChar = rowStart + i
                val chW = paint.measureText(text, i, i + 1)
                var wordIdx = -1
                for (wIdx in spans.indices) {
                    if (globalChar in spans[wIdx]) {
                        wordIdx = wIdx
                        break
                    }
                }
                if (wordIdx >= 0) {
                    val word = timing.words[wordIdx]
                    val sustain = NeveraVocalMotion.vocalHoldEnvelope(word, songPosMs)
                    val baseLift = timing.wordLift(wordIdx, songPosMs) + sustain * 0.42f
                    val buoyancy = NeveraVocalMotion.charWaveBuoyancy(
                        charIndex = globalChar,
                        revealedChars = totalRevealed,
                        word = word,
                        positionMs = songPosMs,
                        isBestPart = isBestPart,
                    )
                    val dy = -(baseLift * 0.65f + buoyancy) * waveLiftPx
                    if (drawGlow && (sustain > 0.05f || isBestPart || buoyancy > 0.25f)) {
                        val gAlpha = ((sustain * 155f + buoyancy * 65f + if (isBestPart) 65f else 0f) * entryAlpha)
                            .roundToInt().coerceIn(0, 195)
                        if (gAlpha > 6) {
                            glowPaint.color = if (isBestPart) {
                                Color.argb(gAlpha, 255, 218, 174)
                            } else {
                                Color.argb(gAlpha, 255, 255, 255)
                            }
                            canvas.drawText(text, i, i + 1, x, baseline + dy, glowPaint)
                        }
                    }
                    canvas.drawText(text, i, i + 1, x, baseline + dy, paint)
                } else {
                    canvas.drawText(text, i, i + 1, x, baseline, paint)
                }
                x += chW
            }
        }

        private fun measureAtChar(text: String, paint: Paint, chars: Float): Float {
            if (text.isEmpty() || chars <= 0f) return 0f
            val idx = chars.toInt().coerceIn(0, text.length)
            val here = paint.measureText(text, 0, idx)
            if (idx >= text.length) return here
            val next = paint.measureText(text, 0, idx + 1)
            return here + (next - here) * (chars - idx)
        }

        private fun wrapMaxTwoLines(text: String, paint: Paint, maxW: Float): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return listOf("")
            if (paint.measureText(trimmed) <= maxW) return listOf(trimmed)
            val firstFit = paint.breakText(trimmed, true, maxW, null).coerceAtLeast(1)
            val space = trimmed.lastIndexOf(' ', (firstFit - 1).coerceAtLeast(0))
            val splitAt = if (space > 0 && firstFit < trimmed.length) space else firstFit
            val first = trimmed.substring(0, splitAt).trimEnd()
            val rest = trimmed.substring(splitAt).trimStart()
            if (rest.isEmpty()) return listOf(first)
            return listOf(first, ellipsizeText(rest, paint, maxW))
        }

        private fun ellipsizeText(text: String, paint: Paint, maxW: Float): String {
            if (paint.measureText(text) <= maxW) return text
            val fit = paint.breakText(text, true, (maxW - paint.measureText("…")).coerceAtLeast(1f), null)
            return text.substring(0, fit.coerceIn(0, text.length)).trimEnd() + "…"
        }

        private fun isRtlText(text: String): Boolean =
            !java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
    }

    companion object {
        private const val ACTION_STOP = "com.nevera.corporation.ACTION_STOP_LYRICS_OVERLAY"

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun openPermissionSettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

        fun sync(context: Context, enabled: Boolean) {
            if (enabled && canDraw(context)) {
                runCatching {
                    context.startService(Intent(context, FloatingLyricsOverlayService::class.java))
                }
            } else {
                runCatching {
                    context.stopService(Intent(context, FloatingLyricsOverlayService::class.java))
                }
            }
        }
    }
}
