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
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Borderless, frameless floating karaoke lyrics overlay drawn directly above
 * other apps via [WindowManager] (requires [Settings.canDrawOverlays]).
 *
 * Mirrors Nevera's playback karaoke lyrics: zero dark box/frame/header,
 * 60/120fps syllable sweep with feathered leading edge, per-character wave
 * bounce, Reff/Best Part luminous glow, automatic Romaji sub-line, drag to
 * reposition anywhere, and double-tap to close.
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
            dp(116f),
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
     * Borderless, VSync-driven Canvas view drawing live karaoke lyrics with:
     * - Zero box/container background
     * - Crisp dark drop-shadow contour readable over light or dark apps
     * - Per-character Gaussian wave bounce + micro-scale synced to lyric timing
     * - Soft feathered karaoke wipe (lit white over translucent unsung letters)
     * - Warm luminous halo during Reff / Best Part climax
     * - Optional Romaji sub-line cleanly centered below
     */
    private class KaraokeOverlayView(context: Context) : View(context) {
        private var snapshot: OverlayLyricSnapshot = OverlayLyricSnapshot()
        private var lastKey: String = ""
        private var transitionStartMs: Long = 0L

        private val density = context.resources.displayMetrics.density
        private val scaledDensity = context.resources.displayMetrics.scaledDensity

        private val jakartaBold: Typeface =
            runCatching { ResourcesCompat.getFont(context, R.font.jakarta_bold) }.getOrNull()
                ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        private val jakartaMedium: Typeface =
            runCatching { ResourcesCompat.getFont(context, R.font.jakarta_medium) }.getOrNull()
                ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaBold
            textSize = 20.5f * scaledDensity
            color = Color.TRANSPARENT
            setShadowLayer(8f * density, 0f, 1.8f * density, 0xD8000000.toInt())
        }

        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaBold
            textSize = 20.5f * scaledDensity
            color = Color.argb(148, 255, 255, 255)
            setShadowLayer(6f * density, 0f, 1.5f * density, 0xC4000000.toInt())
        }

        private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaBold
            textSize = 20.5f * scaledDensity
            color = Color.WHITE
            setShadowLayer(5f * density, 0f, 1.2f * density, 0xB8000000.toInt())
        }

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaBold
            textSize = 20.5f * scaledDensity
            color = Color.argb(135, 255, 232, 205)
            maskFilter = BlurMaskFilter(7.5f * density, BlurMaskFilter.Blur.NORMAL)
        }

        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = jakartaMedium
            textSize = 13.5f * scaledDensity
            color = Color.argb(228, 244, 240, 232)
            textAlign = Paint.Align.CENTER
            setShadowLayer(5f * density, 0f, 1.2f * density, 0xCC000000.toInt())
        }

        private val fadePaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        private val waveLiftPx = 4.2f * density
        private val featherPx = 22f * density

        init {
            setBackgroundColor(Color.TRANSPARENT)
            // Software layer supports BlurMaskFilter + setShadowLayer cleanly across all Android GPUs.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun updateSnapshot(newSnapshot: OverlayLyricSnapshot) {
            val newKey = "${newSnapshot.line?.timeMs ?: -1L}:${newSnapshot.primaryText}"
            if (newKey != lastKey) {
                lastKey = newKey
                transitionStartMs = SystemClock.elapsedRealtime()
            }
            snapshot = newSnapshot
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            if (w <= 0f) return

            val primary = snapshot.primaryText.ifBlank {
                if (snapshot.trackTitle.isNotBlank()) "♪ ${snapshot.trackTitle}" else "♪"
            }
            val nowElapsed = SystemClock.elapsedRealtime()
            val entryT = ((nowElapsed - transitionStartMs).toFloat() / 220f).coerceIn(0f, 1f)
            val smoothEntry = entryT * entryT * (3f - 2f * entryT)
            val slideY = (1f - smoothEntry) * 9f * density

            // Auto-fit font size smoothly if the line is long so nothing ever gets clipped awkwardly.
            val maxTextW = w - 24f * density
            val baseSize = 20.5f * scaledDensity
            val minSize = 14.5f * scaledDensity
            litPaint.textSize = baseSize
            var measuredW = litPaint.measureText(primary)
            val fitSize = if (measuredW > maxTextW && measuredW > 0f) {
                (baseSize * (maxTextW / measuredW)).coerceIn(minSize, baseSize)
            } else {
                baseSize
            }
            shadowPaint.textSize = fitSize
            dimPaint.textSize = fitSize
            litPaint.textSize = fitSize
            glowPaint.textSize = fitSize

            // Split into at most 2 clean wrapped lines if still wider than maxTextW
            val wrapped = wrapMaxTwoLines(primary, litPaint, maxTextW)
            val lineHeight = (litPaint.descent() - litPaint.ascent()) * 1.18f
            val firstBaseline = 26f * density - litPaint.ascent() + slideY

            val timing = snapshot.line
            val songPosMs = if (snapshot.isPlaying && snapshot.anchorElapsedMs > 0L) {
                snapshot.anchorPositionMs + (nowElapsed - snapshot.anchorElapsedMs).coerceIn(0L, 4_000L)
            } else {
                snapshot.anchorPositionMs
            }

            val isBestPart = snapshot.isBestPart
            val waveScale = if (isBestPart) 1.45f else 1.0f
            val canSweep = timing != null && timing.isWordSynced && !isRtlText(primary)
            val totalRevealed = if (canSweep && timing != null) {
                timing.revealedChars(songPosMs).coerceIn(0f, primary.length.toFloat())
            } else {
                primary.length.toFloat()
            }

            // Animate softly across wrapped lines
            var charOffset = 0
            for (rowIdx in wrapped.indices) {
                val rowText = wrapped[rowIdx]
                val rowStart = primary.indexOf(rowText, charOffset).takeIf { it >= 0 } ?: charOffset
                charOffset = rowStart + rowText.length
                val rowW = litPaint.measureText(rowText)
                val startX = (w - rowW) * 0.5f
                val baseline = firstBaseline + rowIdx * lineHeight

                if (!canSweep) {
                    litPaint.alpha = (255 * smoothEntry).roundToInt()
                    if (isBestPart) {
                        glowPaint.alpha = (115 * smoothEntry).roundToInt()
                        canvas.drawText(rowText, startX, baseline, glowPaint)
                    }
                    canvas.drawText(rowText, startX, baseline, litPaint)
                } else {
                    val rowRevealed = (totalRevealed - rowStart).coerceIn(0f, rowText.length.toFloat())
                    val sweepEdgeX = startX + measureAtChar(rowText, litPaint, rowRevealed)

                    // 1. Unsung dim pass with per-character wave
                    dimPaint.alpha = (142 * smoothEntry).roundToInt()
                    drawWavedText(
                        canvas = canvas,
                        text = rowText,
                        startX = startX,
                        baseline = baseline,
                        paint = dimPaint,
                        revealed = rowRevealed,
                        waveBoost = waveScale,
                        drawGlow = false,
                        entryAlpha = smoothEntry,
                        isBestPart = isBestPart,
                    )

                    // 2. Lit karaoke pass with feathered edge & active character wave + glow
                    if (rowRevealed > 0.01f) {
                        litPaint.alpha = (255 * smoothEntry).roundToInt()
                        val topClip = baseline + litPaint.ascent() - waveLiftPx * 2.5f
                        val bottomClip = baseline + litPaint.descent() + 6f * density
                        if (rowRevealed >= rowText.length - 0.01f) {
                            drawWavedText(
                                canvas = canvas,
                                text = rowText,
                                startX = startX,
                                baseline = baseline,
                                paint = litPaint,
                                revealed = rowRevealed,
                                waveBoost = waveScale,
                                drawGlow = isBestPart,
                                entryAlpha = smoothEntry,
                                isBestPart = isBestPart,
                            )
                        } else {
                            val activeFeather = minOf(featherPx, max(4f, sweepEdgeX - startX))
                            val save = canvas.saveLayer(0f, topClip, w, bottomClip, null)
                            canvas.clipRect(0f, topClip, sweepEdgeX + activeFeather, bottomClip)
                            drawWavedText(
                                canvas = canvas,
                                text = rowText,
                                startX = startX,
                                baseline = baseline,
                                paint = litPaint,
                                revealed = rowRevealed,
                                waveBoost = waveScale,
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

            // Optional Romaji / secondary line underneath (borderless with soft shadow)
            val sub = snapshot.secondaryText
            if (sub.isNotBlank() && sub != primary) {
                val subBaseline = firstBaseline + (wrapped.size - 1) * lineHeight +
                    litPaint.descent() + 6f * density - subPaint.ascent()
                subPaint.alpha = (224 * smoothEntry).roundToInt()
                val clippedSub = ellipsizeText(sub, subPaint, maxTextW)
                canvas.drawText(clippedSub, w * 0.5f, subBaseline, subPaint)
            }

            // Keep animating smoothly at display refresh rate while playing or mid-transition
            if (snapshot.isPlaying || entryT < 1f) {
                postInvalidateOnAnimation()
            }
        }

        private fun drawWavedText(
            canvas: Canvas,
            text: String,
            startX: Float,
            baseline: Float,
            paint: Paint,
            revealed: Float,
            waveBoost: Float,
            drawGlow: Boolean,
            entryAlpha: Float,
            isBestPart: Boolean,
        ) {
            if (text.isEmpty()) return
            var x = startX
            for (i in text.indices) {
                val chW = paint.measureText(text, i, i + 1)
                val dist = (i + 0.5f) - revealed
                // Smooth Gaussian wave crest riding cleanly across each character as it is sung
                val wave = if (dist in -3.2f..1.8f && revealed > 0.05f && revealed < text.length + 1.5f) {
                    exp((-dist * dist * 0.42f).toDouble()).toFloat()
                } else {
                    0f
                }
                if (wave > 0.015f) {
                    val dy = -wave * waveLiftPx * waveBoost
                    val scale = 1f + wave * (if (isBestPart) 0.085f else 0.055f)
                    val cx = x + chW * 0.5f
                    val cy = baseline + paint.ascent() * 0.4f
                    canvas.save()
                    canvas.translate(0f, dy)
                    canvas.scale(scale, scale, cx, cy)
                    if (drawGlow) {
                        glowPaint.color = if (isBestPart) {
                            Color.argb((185 * wave * entryAlpha).roundToInt(), 255, 206, 152)
                        } else {
                            Color.argb((125 * wave * entryAlpha).roundToInt(), 255, 255, 255)
                        }
                        canvas.drawText(text, i, i + 1, x, baseline, glowPaint)
                    }
                    canvas.drawText(text, i, i + 1, x, baseline, paint)
                    canvas.restore()
                } else {
                    if (drawGlow && isBestPart) {
                        glowPaint.color = Color.argb((75 * entryAlpha).roundToInt(), 255, 214, 170)
                        canvas.drawText(text, i, i + 1, x, baseline, glowPaint)
                    }
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
