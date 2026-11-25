package com.music.bitchord.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.NeveraVocalMotion
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.replay.Fonts
import com.music.bitchord.ui.replay.ellipsised
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The 7 approved Nevera Share Lyric FX templates selectable in [AndroidLyricsShareSheet].
 */
internal enum class ShareFxTemplate(val label: String) {
    VINYL_PLAQUE("3D Vinyl"),
    HOLO_DECK("3D Holo Deck"),
    PROJECTOR_POLAROID("Projector"),
    HOLO_CD("3D Holo CD"),
    ACRYLIC_GLASS("3D Glass"),
    CINEMA_POSTER("3D Poster"),
    CLEAN_STORY("Clean Story"),
}

/**
 * Software-canvas 3D Floating Plaque, Holo Deck, and Astral Projector Renderer used
 * identically for both live preview in [AndroidLyricsShareSheet] and Media3 MP4 export
 * in [LyricsShareVideo].
 */
internal class NeveraSharePlaqueRenderer(
    context: Context,
    private val song: Song,
    private val cover: Bitmap?,
    private val lines: List<LyricsShareLine>,
    private val template: ShareFxTemplate,
    private val showLogo: Boolean,
) {
    private val fonts = Fonts(context)
    private val monoType: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val serifItalicType: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    // Pre-enhance every lyric line with NeveraVocalMotion so even line-synced LRC songs
    // get silky word-by-word karaoke sweep, wave, and vocal-sustain glow!
    private val enhancedTimings: List<LyricLine?> = lines.mapIndexed { idx, line ->
        if (line.isGap) {
            null
        } else {
            val nextStart = lines.drop(idx + 1).firstOrNull { !it.isGap }?.let { it.startMs ?: it.timing?.timeMs }
            val base = line.timing ?: LyricLine(
                timeMs = line.startMs ?: 0L,
                text = line.text,
                sungUntilMs = line.endMs,
            )
            NeveraVocalMotion.ensureKaraokeTiming(base, nextStart ?: line.endMs)
        }
    }

    // Smooth continuous rolling line index (eliminates abrupt jumps between lyric lines!)
    private var smoothLinePos: Float = 0f
    private var lastElapsedForScroll: Long = -1L

    // Offscreen plaque surface projected into 3D space via Camera + Matrix
    private val plaqueW = 920
    private val plaqueH = 800
    private val plaqueBitmap = Bitmap.createBitmap(plaqueW, plaqueH, Bitmap.Config.ARGB_8888)
    private val plaqueCanvas = Canvas(plaqueBitmap)

    // Offscreen card surface for 3D Holo Deck card & Projector Polaroid print.
    // Rendered at 2x the logical 420x504 space and downscaled by the draw
    // matrices: the Polaroid print is shown at ~1.18x logical size, so a 1x
    // bitmap came out visibly soft/pixelated (its title text especially).
    private val holoCardW = 420
    private val holoCardH = 504
    private val holoCardBitmap = Bitmap.createBitmap(
        holoCardW * HOLO_CARD_SS, holoCardH * HOLO_CARD_SS, Bitmap.Config.ARGB_8888,
    )
    private val holoCardCanvas = Canvas(holoCardBitmap)

    private val camera = Camera()
    private val matrix = Matrix()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = monoType
        textSize = 31f
        color = Color.WHITE
    }
    private val scriptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = serifItalicType
        textSize = 54f
        color = Color.WHITE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(122, 0, 0, 0)
        maskFilter = BlurMaskFilter(44f, BlurMaskFilter.Blur.NORMAL)
    }
    private val blurFlakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
    }
    private val softFlakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
    }
    private val dofTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = serifItalicType
        textAlign = Paint.Align.CENTER
    }

    // Constant-radius blur filters, built once: several scenes used to
    // allocate a fresh BlurMaskFilter on every frame (a native object per
    // frame is exactly the churn that made the sheet's preview judder).
    private val blur14 = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    private val blur22 = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
    private val blur24 = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    private val blur26 = BlurMaskFilter(26f, BlurMaskFilter.Blur.NORMAL)

    /**
     * The plaque scenes' backdrop vignette, cached by frame size: it is a
     * full-screen gradient that never changes while the size doesn't, and it
     * was being rebuilt on every drawn frame.
     */
    private var vignetteShader: RadialGradient? = null
    private var vignetteW = 0f
    private var vignetteH = 0f

    private fun vignette(w: Float, h: Float): RadialGradient {
        val cached = vignetteShader
        if (cached != null && vignetteW == w && vignetteH == h) return cached
        return RadialGradient(
            w * 0.5f, h * 0.48f, w * 0.85f,
            intArrayOf(Color.argb(65, 16, 12, 16), Color.argb(195, 7, 7, 9)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        ).also { vignetteShader = it; vignetteW = w; vignetteH = h }
    }

    // 36 deterministic 3D snow/dust/ash depth flakes
    private data class Flake(
        val depth: Int,
        val x0: Float,
        val y0: Float,
        val speed: Float,
        val drift: Float,
        val rx: Float,
        val ry: Float,
        val rot0: Float,
        val rotSpeed: Float,
        val alpha: Int,
    )

    private val flakes: List<Flake> = List(36) { i ->
        val depth = i % 3
        Flake(
            depth = depth,
            x0 = ((i * 53 + 19) % 100) / 100f,
            y0 = ((i * 37 + 7) % 100) / 100f,
            speed = (if (depth == 2) 0.082f else if (depth == 1) 0.048f else 0.024f) + ((i * 11) % 7) * 0.005f,
            drift = (if (i % 2 == 0) 1f else -1f) * (0.014f + ((i * 13) % 5) * 0.006f),
            rx = if (depth == 2) 7.5f + (i % 6) * 2.4f else if (depth == 1) 2.8f + (i % 4) * 1.1f else 1.7f + (i % 3) * 0.7f,
            ry = if (depth == 2) 13.0f + (i % 5) * 3.2f else if (depth == 1) 4.4f + (i % 4) * 1.4f else 2.4f + (i % 3) * 0.8f,
            rot0 = ((i * 47) % 360).toFloat(),
            rotSpeed = (if (i % 2 == 0) 1f else -1f) * (22f + (i % 5) * 14f),
            alpha = if (depth == 2) 86 else if (depth == 1) 158 else 96,
        )
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        baseBackdrop: Bitmap,
        positionMs: Long?,
        elapsedMs: Long,
        totalMs: Long,
        activeIndex: Int,
        transparentBackdrop: Boolean,
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tSec = elapsedMs / 1000f

        // Advance smooth continuous rolling line index for buttery line transitions
        val targetIdx = activeIndex.coerceAtLeast(0).toFloat()
        if (lastElapsedForScroll < 0L || abs(elapsedMs - lastElapsedForScroll) > 650L || abs(targetIdx - smoothLinePos) > 2.5f) {
            smoothLinePos = targetIdx
        } else {
            val dtSec = ((elapsedMs - lastElapsedForScroll).coerceIn(8L, 64L)) / 1000f
            val k = 1f - exp((-dtSec / 0.145f).toDouble()).toFloat()
            smoothLinePos += (targetIdx - smoothLinePos) * k
        }
        lastElapsedForScroll = elapsedMs

        val progress = if (totalMs > 0L) (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0.42f

        when (template) {
            ShareFxTemplate.HOLO_DECK -> {
                drawHoloDeckScene(canvas, w, h, positionMs, tSec, progress, transparentBackdrop)
                return
            }
            ShareFxTemplate.PROJECTOR_POLAROID -> {
                drawProjectorPolaroidScene(canvas, w, h, positionMs, tSec, transparentBackdrop)
                return
            }
            else -> Unit
        }

        // Standard 3D Plaque Scenes (3D Vinyl, 3D Holo CD, 3D Glass, 3D Poster)
        if (!transparentBackdrop) {
            canvas.drawBitmap(baseBackdrop, 0f, 0f, bitmapPaint)
            paint.shader = vignette(w, h)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        drawFlakes(canvas, w, h, tSec, foreground = false)

        val safeIdx = activeIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val activeLine = lines.getOrNull(safeIdx)
        val activeTiming = enhancedTimings.getOrNull(safeIdx)

        when (template) {
            ShareFxTemplate.VINYL_PLAQUE -> renderVinylOrCdPlaque(activeLine, activeTiming, positionMs, tSec, progress, isHoloCd = false)
            ShareFxTemplate.HOLO_CD -> renderVinylOrCdPlaque(activeLine, activeTiming, positionMs, tSec, progress, isHoloCd = true)
            ShareFxTemplate.ACRYLIC_GLASS -> renderAcrylicOrPosterPlaque(activeLine, activeTiming, positionMs, progress, isPoster = false)
            ShareFxTemplate.CINEMA_POSTER -> renderAcrylicOrPosterPlaque(activeLine, activeTiming, positionMs, progress, isPoster = true)
            else -> Unit
        }

        val rotYDeg = (sin(tSec * 0.68) * 13.5 - 4.5).toFloat()
        val rotXDeg = (cos(tSec * 0.54) * 6.5 + 2.5).toFloat()
        val rotZDeg = (sin(tSec * 0.42) * 1.6).toFloat()
        val hoverY = (sin(tSec * 1.15) * 16.0).toFloat()
        val fitScale = ((w * 0.91f) / plaqueW).coerceAtMost((h * 0.72f) / plaqueH)

        canvas.save()
        canvas.translate(w * 0.5f, h * 0.50f + hoverY + 42f)
        canvas.scale(fitScale, fitScale)
        canvas.drawRoundRect(
            -plaqueW * 0.43f, -plaqueH * 0.41f,
            plaqueW * 0.43f, plaqueH * 0.41f,
            24f, 24f, shadowPaint,
        )
        canvas.restore()

        matrix.reset()
        camera.save()
        camera.setLocation(0f, 0f, -26f)
        camera.rotateX(rotXDeg)
        camera.rotateY(rotYDeg)
        camera.rotateZ(rotZDeg)
        camera.getMatrix(matrix)
        camera.restore()

        matrix.preTranslate(-plaqueW * 0.5f, -plaqueH * 0.5f)
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate(w * 0.5f, h * 0.50f + hoverY)

        canvas.drawBitmap(plaqueBitmap, matrix, bitmapPaint)
        drawFlakes(canvas, w, h, tSec, foreground = true)
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEMPLATE: 3D HOLO DECK (Futuristic Transparent Acrylic Deck + Levitating Original Album Card)
    // ══════════════════════════════════════════════════════════════════════

    private fun drawHoloDeckScene(
        canvas: Canvas,
        w: Float,
        h: Float,
        positionMs: Long?,
        tSec: Float,
        progress: Float,
        transparentBackdrop: Boolean,
    ) {
        val bassPulse = (0.35f + 0.65f * abs(sin(tSec * 3.8)).toFloat())

        if (!transparentBackdrop) {
            paint.shader = RadialGradient(
                w * 0.5f, h * 0.56f, w * 0.95f,
                intArrayOf(Color.rgb(20, 27, 50), Color.rgb(8, 9, 15)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // 1. Bottom Futuristic Transparent Acrylic & Matte-Black Circular CD/Turntable Deck
        val cx = w * 0.5f
        val deckTopY = h * 0.745f
        val deckH = h * 0.088f
        val outerRx = w * 0.41f
        val outerRy = outerRx * 0.285f
        val innerRx = outerRx * 0.86f
        val innerRy = outerRy * 0.80f

        // Floor ambient ice-blue glow reflection
        paint.shader = RadialGradient(
            cx, deckTopY + deckH + 18f, outerRx * 1.15f,
            intArrayOf(Color.argb(82, 118, 166, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(
            cx - outerRx * 1.12f, deckTopY + deckH - outerRy * 0.4f,
            cx + outerRx * 1.12f, deckTopY + deckH + outerRy * 1.2f,
            paint,
        )
        paint.shader = null

        // Transparent acrylic outer cylinder wall with metallic mechanical cutouts
        val wallPath = Path().apply {
            moveTo(cx - outerRx, deckTopY)
            lineTo(cx - outerRx, deckTopY + deckH)
            arcTo(
                RectF(cx - outerRx, deckTopY + deckH - outerRy, cx + outerRx, deckTopY + deckH + outerRy),
                180f, -180f, false,
            )
            lineTo(cx + outerRx, deckTopY)
            arcTo(
                RectF(cx - outerRx, deckTopY - outerRy, cx + outerRx, deckTopY + outerRy),
                0f, 180f, false,
            )
            close()
        }
        paint.shader = LinearGradient(
            cx - outerRx, deckTopY, cx + outerRx, deckTopY,
            intArrayOf(
                Color.argb(102, 188, 206, 238),
                Color.argb(165, 36, 40, 54),
                Color.argb(118, 138, 150, 172),
                Color.argb(185, 24, 28, 38),
                Color.argb(122, 166, 178, 202),
                Color.argb(108, 205, 222, 252),
            ),
            floatArrayOf(0f, 0.16f, 0.36f, 0.52f, 0.74f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(wallPath, paint)
        paint.shader = null

        // Mechanical internal silver blocks clipped inside acrylic cylinder
        canvas.save()
        canvas.clipPath(wallPath)
        val segs = 7
        val segStep = (outerRx * 2f - 84f) / segs
        for (i in 0 until segs) {
            val fx = cx - outerRx + 42f + i * segStep
            paint.color = if (i % 2 == 0) Color.argb(92, 182, 192, 212) else Color.argb(170, 18, 20, 28)
            canvas.drawRoundRect(fx, deckTopY + outerRy * 0.45f, fx + segStep * 0.72f, deckTopY + deckH + outerRy * 0.4f, 12f, 12f, paint)
        }
        canvas.restore()

        // Luminous ice-blue LED bottom rim along the acrylic cylinder
        glowPaint.shader = null
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeWidth = 8f
        glowPaint.color = Color.argb(215, 148, 192, 255)
        glowPaint.maskFilter = blur22
        canvas.drawArc(
            RectF(cx - outerRx, deckTopY + deckH - outerRy, cx + outerRx, deckTopY + deckH + outerRy),
            6f, 168f, false, glowPaint,
        )
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 4.5f
        canvas.drawArc(
            RectF(cx - outerRx, deckTopY + deckH - outerRy, cx + outerRx, deckTopY + deckH + outerRy),
            6f, 168f, false, strokePaint,
        )

        // Matte-black circular spinning deck platter
        paint.shader = RadialGradient(
            cx - 60f, deckTopY - 22f, innerRx,
            intArrayOf(Color.rgb(38, 41, 52), Color.rgb(17, 19, 27), Color.rgb(9, 11, 16)),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(cx - innerRx, deckTopY - innerRy, cx + innerRx, deckTopY + innerRy, paint)
        paint.shader = null

        // Subtle spinning specular sheen wedge on the black platter
        val spinDeg = (tSec * 78f) % 360f
        paint.color = Color.argb(20, 210, 226, 255)
        val platRect = RectF(cx - innerRx, deckTopY - innerRy, cx + innerRx, deckTopY + innerRy)
        canvas.drawArc(platRect, spinDeg, 36f, true, paint)
        canvas.drawArc(platRect, (spinDeg + 180f) % 360f, 36f, true, paint)

        // Clean "M U S I C" deck engraving (matches reference!)
        val deckMark = fonts.body(22f, Color.argb(205, 224, 232, 248), bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("M U S I C", cx, deckTopY + innerRy - 18f, deckMark)

        // Iconic Glowing White/Ice-Blue Center LED Ring
        val ringRx = outerRx * 0.42f
        val ringRy = outerRy * 0.38f
        val ringRect = RectF(cx - ringRx, deckTopY - ringRy, cx + ringRx, deckTopY + ringRy)
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeWidth = 14f + bassPulse * 4f
        glowPaint.color = Color.argb(225, 155, 198, 255)
        glowPaint.maskFilter = blur24
        canvas.drawOval(ringRect, glowPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 10f
        canvas.drawOval(ringRect, strokePaint)

        // Central recessed hub + chrome spindle pin
        paint.color = Color.rgb(8, 9, 14)
        canvas.drawOval(cx - ringRx * 0.62f, deckTopY - ringRy * 0.62f, cx + ringRx * 0.62f, deckTopY + ringRy * 0.62f, paint)
        paint.shader = LinearGradient(cx - 14f, 0f, cx + 14f, 0f, Color.rgb(72, 78, 94), Color.WHITE, Shader.TileMode.MIRROR)
        canvas.drawRoundRect(cx - 12f, deckTopY - 38f, cx + 12f, deckTopY + 4f, 6f, 6f, paint)
        paint.shader = null

        // 2. Back 3D Orbital Arcs
        drawHoloOrbitalArcs(canvas, w, h, tSec, front = false, arcColor = Color.argb(215, 218, 234, 255))

        // 3. Smoothly Rolling Bigger Tilted Script Karaoke Lyrics RIGHT behind the upper album card!
        drawRollingDepthLyrics(
            canvas = canvas,
            anchorX = w * 0.50f,
            anchorY = h * 0.265f,
            maxW = w * 0.78f,
            baseFontSize = 62f,
            linePitch = 82f,
            tiltDeg = -8.5f,
            positionMs = positionMs,
            litEndColor = Color.rgb(198, 224, 255),
            glowColor = Color.argb(190, 155, 198, 255),
        )

        // 4. Levitating Tilted 3D-Perspective Original Album Card (ZERO "Nevera" branding on card!)
        renderHoloDeckAlbumCard(tSec, positionMs ?: 0L, progress, bassPulse)
        val hoverY = (sin(tSec * 1.25) * 16.0).toFloat()
        val swayYDeg = (sin(tSec * 0.90) * 20.0).toFloat()
        val tiltZDeg = (-10.5 + sin(tSec * 0.62) * 2.4).toFloat()
        val cardScale = (w * 0.37f) / holoCardW

        // Translucent blurred ghost cards peeking left & right behind main card
        fun drawGhost(ox: Float, oy: Float, extraZ: Float, alpha: Int) {
            canvas.save()
            canvas.translate(w * 0.5f + ox, h * 0.505f + hoverY + oy)
            canvas.rotate(tiltZDeg + extraZ)
            paint.color = Color.argb(alpha, 46, 54, 78)
            val gw = holoCardW * cardScale * 0.88f
            val gh = holoCardH * cardScale * 0.88f
            canvas.drawRoundRect(-gw * 0.5f, -gh * 0.5f, gw * 0.5f, gh * 0.5f, 22f, 22f, paint)
            canvas.restore()
        }
        drawGhost(-74f, 24f, -10f, 82)
        drawGhost(74f, 12f, 9f, 70)

        matrix.reset()
        camera.save()
        camera.setLocation(0f, 0f, -22f)
        camera.rotateY(swayYDeg)
        camera.rotateZ(tiltZDeg)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-holoCardW * 0.5f, -holoCardH * 0.5f)
        matrix.preScale(1f / HOLO_CARD_SS, 1f / HOLO_CARD_SS)
        matrix.postScale(cardScale, cardScale)
        matrix.postTranslate(w * 0.5f, h * 0.505f + hoverY)
        canvas.drawBitmap(holoCardBitmap, matrix, bitmapPaint)

        if (showLogo) drawShareCredit(canvas, w, h)

        // 5. Front 3D Orbital Arcs + Twinkling Starlight Dots
        drawHoloOrbitalArcs(canvas, w, h, tSec, front = true, arcColor = Color.argb(230, 232, 242, 255))
    }

    private fun renderHoloDeckAlbumCard(tSec: Float, posMs: Long, progress: Float, bass: Float) {
        holoCardBitmap.eraseColor(Color.TRANSPARENT)
        val c = holoCardCanvas
        // Draw in logical 420x504 space on the 2x surface (see holoCardBitmap).
        c.save()
        c.scale(HOLO_CARD_SS.toFloat(), HOLO_CARD_SS.toFloat())
        val bw = holoCardW.toFloat()
        val bh = holoCardH.toFloat()
        val rect = RectF(4f, 4f, bw - 4f, bh - 4f)

        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, 26f, 26f, Path.Direction.CW) })

        // Pure original album cover filling card
        if (cover != null && !cover.isRecycled) {
            val scale = maxOf(bw / cover.width, bh / cover.height)
            val sw = cover.width * scale
            val sh = cover.height * scale
            c.drawBitmap(cover, null, RectF((bw - sw) * 0.5f, (bh - sh) * 0.5f, (bw + sw) * 0.5f, (bh + sh) * 0.5f), bitmapPaint)
        } else {
            paint.shader = LinearGradient(0f, 0f, bw, bh, Color.rgb(34, 52, 102), Color.rgb(185, 118, 128), Shader.TileMode.CLAMP)
            c.drawRect(rect, paint)
            paint.shader = null
        }

        // Top & bottom crisp readability scrims
        paint.shader = LinearGradient(0f, 0f, 0f, bh * 0.45f, Color.argb(188, 10, 14, 28), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw, bh * 0.46f, paint)
        paint.shader = LinearGradient(0f, bh * 0.68f, 0f, bh, Color.TRANSPARENT, Color.argb(215, 8, 10, 18), Shader.TileMode.CLAMP)
        c.drawRect(0f, bh * 0.68f, bw, bh, paint)
        paint.shader = null

        // Top-left Title & Artist (NO "Nevera" branding!)
        val tP = fonts.heading(32f, Color.WHITE)
        val aP = fonts.body(21f, Color.argb(205, 232, 238, 255))
        c.drawText(ellipsised(song.title.uppercase(), tP, bw - 95f), 28f, 58f, tP)
        c.drawText(ellipsised("- ${song.artist}", aP, bw - 95f), 28f, 92f, aP)

        // Top-right 4-point sparkle star (✦)
        drawFourPointStar(c, bw - 42f, 48f, 16f, Color.WHITE)

        // Bottom mini live equalizer + dotted scrubber + timestamp
        val by = bh - 40f
        for (b in 0 until 4) {
            val eH = 9f + abs(sin(tSec * 7.5 + b * 1.4)).toFloat() * (10f + bass * 12f)
            paint.color = Color.WHITE
            c.drawRect(28f + b * 9.5f, by - eH * 0.5f, 32.5f + b * 9.5f, by + eH * 0.5f, paint)
        }
        val barStart = 76f
        val barEnd = bw - 82f
        val dots = 22
        val litCount = (progress * dots).roundToInt().coerceIn(0, dots)
        for (d in 0 until dots) {
            val dx = barStart + (d.toFloat() / (dots - 1)) * (barEnd - barStart)
            val isLit = d < litCount
            val dh = if (isLit) (5f + ((d * 7) % 5) * 2.4f) else 4.2f
            paint.color = if (isLit) Color.WHITE else Color.argb(95, 255, 255, 255)
            c.drawRoundRect(dx - 2.2f, by - dh * 0.5f, dx + 2.2f, by + dh * 0.5f, 2.2f, 2.2f, paint)
        }
        val secs = (posMs / 1000L).coerceAtLeast(0L)
        val ts = "${secs / 60}:${"%02d".format(secs % 60)}"
        val tsP = fonts.body(19f, Color.argb(235, 245, 248, 255), bold = true).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(ts, bw - 26f, by + 7f, tsP)

        c.restore()

        strokePaint.color = Color.argb(195, 230, 240, 255)
        strokePaint.strokeWidth = 4f
        c.drawRoundRect(rect, 26f, 26f, strokePaint)
        c.restore()
    }

    private fun drawHoloOrbitalArcs(canvas: Canvas, w: Float, h: Float, tSec: Float, front: Boolean, arcColor: Int) {
        val cx = w * 0.5f
        val cy = h * 0.505f
        strokePaint.color = arcColor
        strokePaint.strokeWidth = 3.4f
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.save()
        if (!front) {
            canvas.save()
            canvas.rotate(-12f + sin(tSec * 0.5).toFloat() * 2.5f, cx, cy)
            canvas.drawArc(RectF(cx - w * 0.30f, cy - 110f, cx + w * 0.32f, cy + 95f), -66f, 80f, false, strokePaint)
            canvas.restore()
            // Upper crown arc above lyrics
            canvas.save()
            canvas.rotate(-8f, cx, h * 0.21f)
            canvas.drawArc(RectF(cx - w * 0.28f, h * 0.14f, cx + w * 0.30f, h * 0.29f), 205f, 95f, false, strokePaint)
            canvas.restore()
        } else {
            canvas.save()
            canvas.rotate(-10f + sin(tSec * 0.5).toFloat() * 2.5f, cx, cy)
            canvas.drawArc(RectF(cx - w * 0.32f, cy - 82f, cx + w * 0.28f, cy + 125f), 124f, 68f, false, strokePaint)
            canvas.drawArc(RectF(cx - w * 0.26f, cy + 12f, cx + w * 0.33f, cy + 205f), 10f, 48f, false, strokePaint)
            canvas.restore()
            // Twinkling starlight motes
            paint.shader = null
            for (i in 0 until 14) {
                val sx = (0.14f + ((i * 43 + 11) % 72) / 100f) * w
                val sy = (0.18f + ((i * 59 + 7) % 52) / 100f) * h + sin(tSec * 0.9 + i * 1.3).toFloat() * 9f
                val a = (90 + abs(sin(tSec * 1.6 + i * 1.4)).toFloat() * 145).roundToInt()
                paint.color = Color.argb(a, 235, 244, 255)
                canvas.drawCircle(sx, sy, 2.2f + (i % 4) * 1.2f, paint)
            }
        }
        canvas.restore()
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEMPLATE: PROJECTOR POLAROID (Mini Projector + Volumetric God-Rays + Rolling Serif + Polaroid)
    // ══════════════════════════════════════════════════════════════════════

    private fun drawProjectorPolaroidScene(
        canvas: Canvas,
        w: Float,
        h: Float,
        positionMs: Long?,
        tSec: Float,
        transparentBackdrop: Boolean,
    ) {
        val bass = (0.35f + 0.65f * abs(sin(tSec * 3.8)).toFloat())

        if (!transparentBackdrop) {
            paint.shader = RadialGradient(
                w * 0.48f, h * 0.46f, maxOf(w, h) * 0.85f,
                intArrayOf(Color.rgb(24, 18, 40), Color.rgb(11, 8, 20)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // Faint violet rim flare at top center
        paint.shader = RadialGradient(w * 0.42f, 0f, 240f, Color.argb(105, 212, 192, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, 260f, paint)
        paint.shader = null

        // 1. Mini Hologram Projector Lamp + Volumetric Multi-Ray God-Rays Cone (bottom center)
        val px = w * 0.50f
        val py = h * 0.91f
        val lampW = 118f
        val lampH = 76f
        val beamTopY = h * 0.08f
        val halfSpread = w * 0.54f

        // Main atmospheric volumetric light cone
        val conePath = Path().apply {
            moveTo(px - 36f, py - 20f)
            lineTo(px - halfSpread, beamTopY)
            lineTo(px + halfSpread, beamTopY)
            lineTo(px + 36f, py - 20f)
            close()
        }
        paint.shader = LinearGradient(
            px, py - 20f, px, beamTopY,
            intArrayOf(
                Color.argb((118 + bass * 26).roundToInt(), 218, 198, 255),
                Color.argb((54 + bass * 16).roundToInt(), 150, 122, 224),
                Color.argb(14, 150, 122, 224),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.28f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(conePath, paint)

        // Shimmering volumetric god-ray streaks inside beam
        val rays = 7
        for (r in 0 until rays) {
            val frac = (r.toFloat() / (rays - 1)) * 2f - 1f
            val sway = sin(tSec * 0.9 + r * 1.4).toFloat() * 24f
            val topX = px + frac * halfSpread * 0.85f + sway
            val rayW = 52f + (r % 3) * 28f
            val rPath = Path().apply {
                moveTo(px + frac * 22f - 8f, py - 20f)
                lineTo(topX - rayW * 0.5f, beamTopY)
                lineTo(topX + rayW * 0.5f, beamTopY)
                lineTo(px + frac * 22f + 8f, py - 20f)
                close()
            }
            val a = (28 + 16 * sin(tSec * 1.8 + r * 2.1)).roundToInt().coerceIn(10, 62)
            paint.shader = LinearGradient(
                px, py - 20f, topX, beamTopY,
                Color.argb(a * 2, 226, 208, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(rPath, paint)
        }
        paint.shader = null

        // Glowing dust motes dancing inside projector light cone
        for (i in 0 until 26) {
            val vh = (((i * 61 + 29) % 100) / 100f + tSec * (0.04f + (i % 6) * 0.009f)) % 1f
            val my = (py - 28f) - vh * ((py - 28f) - beamTopY)
            val spread = 34f + vh * halfSpread * 0.88f
            val u = (((i * 47 + 13) % 100) / 100f) * 2f - 1f
            val mx = px + u * spread + sin(tSec * 1.1 + i * 1.7).toFloat() * 14f
            val alpha = (sin(vh * PI).toFloat() * 145f).roundToInt().coerceIn(0, 165)
            paint.color = Color.argb(alpha, 232, 216, 255)
            canvas.drawCircle(mx, my, 1.8f + (i % 4) * 1.1f, paint)
        }

        // Physical miniature cylindrical dark projector lamp + glowing optical lens
        paint.color = Color.argb(185, 0, 0, 0)
        canvas.drawOval(px - lampW * 0.66f, py + lampH * 0.36f, px + lampW * 0.66f, py + lampH * 0.62f, paint)
        paint.shader = LinearGradient(
            px - lampW * 0.5f, py, px + lampW * 0.5f, py,
            intArrayOf(Color.rgb(32, 28, 44), Color.rgb(15, 13, 22), Color.rgb(38, 33, 50)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(px - lampW * 0.5f, py - lampH * 0.42f, px + lampW * 0.5f, py + lampH * 0.48f, 14f, 14f, paint)
        paint.shader = null
        glowPaint.style = Paint.Style.FILL
        glowPaint.color = Color.argb(235, 224, 206, 255)
        glowPaint.maskFilter = blur26
        canvas.drawOval(px - lampW * 0.36f, py - lampH * 0.46f, px + lampW * 0.36f, py - lampH * 0.32f, glowPaint)
        paint.color = Color.WHITE
        canvas.drawOval(px - lampW * 0.32f, py - lampH * 0.44f, px + lampW * 0.32f, py - lampH * 0.34f, paint)

        // 2. Sweeping Orbital Arc + 4-Point Star crowning projected lyrics
        canvas.save()
        val ox = w * 0.48f
        val oy = h * 0.195f
        val orx = w * 0.36f
        val ory = 95f
        strokePaint.color = Color.argb(145, 210, 192, 248)
        strokePaint.strokeWidth = 2.6f
        canvas.save()
        canvas.rotate(-14f, ox, oy)
        canvas.drawArc(RectF(ox - orx, oy - ory, ox + orx, oy + ory), 130f, 180f, false, strokePaint)
        canvas.restore()
        drawFourPointStar(canvas, ox - 34f, oy - 78f, 20f, Color.rgb(240, 232, 255))
        canvas.restore()

        // 3. Projected Rolling Italic Serif Karaoke Lyrics (upper zone inside projector cone!)
        drawRollingDepthLyrics(
            canvas = canvas,
            anchorX = w * 0.50f,
            anchorY = h * 0.255f,
            maxW = w * 0.80f,
            baseFontSize = 68f,
            linePitch = 84f,
            tiltDeg = 0f,
            positionMs = positionMs,
            litEndColor = Color.rgb(214, 196, 255),
            glowColor = Color.argb(200, 208, 188, 255),
        )

        // 4. Floating 3D-Tilted Matte Polaroid Album Print (pure original album, star + barcode!)
        renderPolaroidPrint()
        val hoverY = (sin(tSec * 1.15) * 14.0).toFloat()
        val swayYDeg = (10.0 + sin(tSec * 0.72) * 10.0).toFloat()
        val tiltZDeg = (-4.5 + sin(tSec * 0.55) * 1.8).toFloat()
        val polScale = (w * 0.46f) / holoCardW
        val polCx = w * 0.53f
        val polCy = h * 0.615f + hoverY

        // Delicate halo loop above the Polaroid's upper edge
        strokePaint.color = Color.argb(130, 210, 192, 248)
        strokePaint.strokeWidth = 2.4f
        canvas.save()
        canvas.rotate(-18f, polCx - 30f, polCy - holoCardH * polScale * 0.54f)
        canvas.drawArc(
            RectF(
                polCx - 210f, polCy - holoCardH * polScale * 0.54f - 44f,
                polCx + 150f, polCy - holoCardH * polScale * 0.54f + 44f,
            ),
            168f, 215f, false, strokePaint,
        )
        canvas.restore()

        matrix.reset()
        camera.save()
        camera.setLocation(0f, 0f, -22f)
        camera.rotateY(swayYDeg)
        camera.rotateZ(tiltZDeg)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-holoCardW * 0.5f, -holoCardH * 0.5f)
        matrix.preScale(1f / HOLO_CARD_SS, 1f / HOLO_CARD_SS)
        matrix.postScale(polScale, polScale)
        matrix.postTranslate(polCx, polCy)
        canvas.drawBitmap(holoCardBitmap, matrix, bitmapPaint)

        if (showLogo) drawShareCredit(canvas, w, h)
    }

    /**
     * The "Nevera" credit for the two scenes whose cards deliberately carry no
     * branding of their own (Holo Deck, Projector Polaroid): the sheet's
     * Show-logo toggle was silently ignored by them, so when it is on the
     * credit sits small in the frame's bottom-right corner instead.
     */
    private fun drawShareCredit(canvas: Canvas, w: Float, h: Float) {
        val credit = fonts.body(25f, Color.argb(165, 238, 240, 248), bold = true).apply {
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("Nevera", w - 34f, h - 30f, credit)
    }

    private fun renderPolaroidPrint() {
        holoCardBitmap.eraseColor(Color.TRANSPARENT)
        val c = holoCardCanvas
        // Draw in logical 420x504 space on the 2x surface (see holoCardBitmap).
        c.save()
        c.scale(HOLO_CARD_SS.toFloat(), HOLO_CARD_SS.toFloat())
        val bw = holoCardW.toFloat()
        val bh = holoCardH.toFloat()

        // Matte cool off-white Polaroid paper
        paint.shader = LinearGradient(0f, 0f, bw, bh, Color.rgb(218, 216, 228), Color.rgb(176, 172, 192), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw, bh, paint)
        paint.shader = null

        val pad = 26f
        val artSz = bw - pad * 2f
        drawSquareCover(c, pad, pad, artSz, 0f)

        // Exact reference footer marks: tiny dark 4-point star bottom-left + minimal barcode bottom-right
        val footY = pad + artSz + (bh - (pad + artSz)) * 0.52f
        drawFourPointStar(c, pad + 16f, footY, 13f, Color.rgb(32, 28, 42))

        val pattern = intArrayOf(2, 1, 3, 1, 1, 2, 1, 3, 2, 1, 1, 2, 3, 1, 2, 1, 1, 3, 1, 2)
        var bx = bw - pad - 92f
        paint.color = Color.rgb(34, 31, 45)
        for (i in pattern.indices) {
            val wBar = pattern[i] * 2.4f
            if (i % 2 == 0) c.drawRect(bx, footY - 13f, bx + wBar, footY + 13f, paint)
            bx += pattern[i] * 2.7f
        }
        c.restore()
    }

    /**
     * Smoothly rolling depth-of-field karaoke lyrics shared by Holo Deck & Projector Polaroid:
     * tight uniform line spacing, progressive blur sharpening into active line, and silky
     * liquid karaoke sweep powered by [NeveraVocalMotion].
     */
    private fun drawRollingDepthLyrics(
        canvas: Canvas,
        anchorX: Float,
        anchorY: Float,
        maxW: Float,
        baseFontSize: Float,
        linePitch: Float,
        tiltDeg: Float,
        positionMs: Long?,
        litEndColor: Int,
        glowColor: Int,
    ) {
        if (lines.isEmpty()) return
        canvas.save()
        canvas.translate(anchorX, anchorY)
        if (tiltDeg != 0f) canvas.rotate(tiltDeg)

        val startI = (smoothLinePos.toInt() - 1).coerceAtLeast(0)
        val endI = (smoothLinePos.toInt() + 3).coerceAtMost(lines.lastIndex)

        for (i in startI..endI) {
            val line = lines[i]
            if (line.isGap || line.text.isBlank()) continue
            val rel = i - smoothLinePos
            if (rel < -1.25f || rel > 3.35f) continue

            val absRel = abs(rel)
            val focusRaw = (1f - absRel / 0.92f).coerceIn(0f, 1f)
            val focus = focusRaw * focusRaw * (3f - 2f * focusRaw)
            val yPos = rel * linePitch
            val blurPx = if (rel >= 0f) {
                ((1f - focus) * (4.5f + minOf(rel, 3f) * 2.8f)).coerceAtLeast(0f)
            } else {
                (1f - focus) * 7.5f
            }
            val sc = 0.88f + focus * 0.12f
            val alpha = if (rel < 0f) {
                val t = (1f + rel / 0.95f).coerceIn(0f, 1f)
                t * t * (3f - 2f * t) * 0.90f
            } else {
                focus + (1f - focus) * (0.52f - rel * 0.10f).coerceAtLeast(0.18f)
            }
            if (alpha <= 0.01f) continue

            canvas.save()
            canvas.translate(0f, yPos)
            canvas.scale(sc, sc)

            if (focus > 0.18f) {
                scriptPaint.textSize = baseFontSize
                scriptPaint.maskFilter = if (blurPx > 0.6f) BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) else null
                val wrapped = cappedRows(wrapLines(line.text, scriptPaint, maxW), scriptPaint, maxW, 2)
                val lh = baseFontSize * 1.15f
                val y0 = -((wrapped.size - 1) * lh) * 0.5f

                val timing = enhancedTimings.getOrNull(i)
                val revealed = when {
                    positionMs == null -> line.text.length.toFloat()
                    timing != null -> NeveraVocalMotion.smartRevealedChars(timing, positionMs)
                    else -> line.text.length.toFloat()
                }

                var cursor = 0
                wrapped.forEachIndexed { rIdx, rowText ->
                    val rowStart = line.text.indexOf(rowText, cursor).takeIf { it >= 0 } ?: cursor
                    cursor = rowStart + rowText.length
                    val rowW = scriptPaint.measureText(rowText)
                    val startX = -rowW * 0.5f
                    val y = y0 + rIdx * lh

                    // Unsung soft base. The sweep below leaves its own alpha on
                    // this shared paint, which Android multiplies into the color
                    // alpha — reset it or every later row (and frame) bases dimmer.
                    scriptPaint.shader = null
                    scriptPaint.alpha = 255
                    scriptPaint.color = Color.argb((110 * alpha).roundToInt(), 225, 232, 248)
                    canvas.drawText(rowText, startX, y, scriptPaint)

                    // Lit liquid karaoke sweep
                    val rowRev = (revealed - rowStart).coerceIn(0f, rowText.length.toFloat())
                    if (rowRev > 0.01f) {
                        val kIdx = rowRev.toInt().coerceIn(0, rowText.length)
                        val edgeW = if (kIdx >= rowText.length) {
                            rowW
                        } else {
                            val h0 = scriptPaint.measureText(rowText, 0, kIdx)
                            val h1 = scriptPaint.measureText(rowText, 0, kIdx + 1)
                            h0 + (h1 - h0) * (rowRev - kIdx)
                        }
                        canvas.save()
                        canvas.clipRect(startX - 12f, y - baseFontSize * 1.2f, startX + edgeW + 12f, y + baseFontSize * 0.4f)
                        scriptPaint.shader = LinearGradient(
                            startX, y - baseFontSize, startX + rowW.coerceAtLeast(1f), y,
                            Color.WHITE, litEndColor, Shader.TileMode.CLAMP,
                        )
                        scriptPaint.alpha = (255 * alpha).roundToInt()
                        if (focus > 0.5f) {
                            glowPaint.set(scriptPaint)
                            glowPaint.style = Paint.Style.FILL
                            glowPaint.shader = null
                            glowPaint.color = glowColor
                            glowPaint.alpha = (165 * focus * alpha).roundToInt()
                            glowPaint.maskFilter = blur14
                            canvas.drawText(rowText, startX, y, glowPaint)
                        }
                        canvas.drawText(rowText, startX, y, scriptPaint)
                        canvas.restore()
                        scriptPaint.shader = null
                    }
                }
                scriptPaint.maskFilter = null
            } else {
                dofTextPaint.textSize = baseFontSize * 0.86f
                dofTextPaint.color = Color.argb((255 * alpha).roundToInt(), 220, 226, 246)
                dofTextPaint.maskFilter = if (blurPx > 0.5f) BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) else null
                canvas.drawText(ellipsised(line.text, dofTextPaint, maxW), 0f, 0f, dofTextPaint)
                dofTextPaint.maskFilter = null
            }

            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawFourPointStar(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val path = Path().apply {
            moveTo(0f, -r)
            quadTo(r * 0.16f, -r * 0.16f, r, 0f)
            quadTo(r * 0.16f, r * 0.16f, 0f, r)
            quadTo(-r * 0.16f, r * 0.16f, -r, 0f)
            quadTo(-r * 0.16f, -r * 0.16f, 0f, -r)
            close()
        }
        canvas.save()
        canvas.translate(x, y)
        paint.shader = null
        paint.color = color
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawFlakes(canvas: Canvas, w: Float, h: Float, tSec: Float, foreground: Boolean) {
        for (f in flakes) {
            if (foreground && f.depth != 2) continue
            if (!foreground && f.depth == 2) continue
            val py = ((f.y0 + tSec * f.speed) % 1.14f) - 0.07f
            val px = (((f.x0 + sin(tSec * 0.72 + f.y0 * 8.0).toFloat() * 0.05f + tSec * f.drift) % 1.1f) + 1.1f) % 1.1f - 0.05f
            val cx = px * w
            val cy = py * h
            val p = when (f.depth) {
                2 -> blurFlakePaint
                0 -> softFlakePaint
                else -> paint
            }
            p.shader = null
            p.color = Color.argb(f.alpha, 242, 238, 234)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(f.rot0 + tSec * f.rotSpeed)
            canvas.drawOval(-f.rx, -f.ry, f.rx, f.ry, p)
            canvas.restore()
        }
    }

    private fun renderVinylOrCdPlaque(
        line: LyricsShareLine?,
        timing: LyricLine?,
        positionMs: Long?,
        tSec: Float,
        progress: Float,
        isHoloCd: Boolean,
    ) {
        plaqueBitmap.eraseColor(Color.TRANSPARENT)
        val c = plaqueCanvas
        val pad = 28f
        val cw = plaqueW - pad * 2f
        val ch = plaqueH - pad * 2f
        val rect = RectF(pad, pad, pad + cw, pad + ch)

        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(
                Color.argb(212, 46, 36, 40),
                Color.argb(222, 28, 22, 26),
                Color.argb(232, 18, 16, 20),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rect, 20f, 20f, paint)
        paint.shader = null

        paint.shader = LinearGradient(
            rect.left, rect.top, rect.left + cw * 0.85f, rect.top + ch * 0.65f,
            Color.argb(28, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rect, 20f, 20f, paint)
        paint.shader = null

        c.save()
        val clipPath = Path().apply { addRoundRect(rect, 20f, 20f, Path.Direction.CW) }
        c.clipPath(clipPath)

        val sleeveX = rect.left + 34f
        val sleeveY = rect.top + 34f
        val sleeveSize = 428f
        val discR = 198f
        val discCx = sleeveX + sleeveSize + 92f
        val discCy = sleeveY + sleeveSize * 0.5f
        val spinDeg = (tSec * 82f) % 360f
        drawSpinningDisc(c, discCx, discCy, discR, spinDeg, isHoloCd)
        drawSquareCover(c, sleeveX, sleeveY, sleeveSize, 8f)

        if (showLogo) {
            val badgeCx = rect.right - 44f
            val badgeCy = rect.top + 44f
            paint.color = Color.argb(232, 248, 245, 240)
            c.drawCircle(badgeCx, badgeCy, 22f, paint)
            val markPaint = fonts.heading(22f, Color.rgb(22, 20, 24)).apply { textAlign = Paint.Align.CENTER }
            c.drawText("N", badgeCx, badgeCy + 8f, markPaint)
        }

        if (line != null) {
            drawPlaqueMonospaceLyrics(
                canvas = c,
                line = line,
                timing = timing,
                positionMs = positionMs,
                x = rect.left + 36f,
                centerY = rect.bottom - 134f,
                maxW = 440f,
            )
        }

        val rightX = rect.right - 36f
        val titleP = fonts.heading(42f, Color.rgb(248, 245, 239)).apply { textAlign = Paint.Align.RIGHT }
        val artistP = fonts.body(26f, Color.argb(188, 232, 226, 218)).apply { textAlign = Paint.Align.RIGHT }
        val brandP = fonts.body(27f, Color.argb(222, 245, 240, 232), bold = true).apply { textAlign = Paint.Align.RIGHT }
        val maxCreditW = 340f
        c.drawText(ellipsised(song.title, titleP, maxCreditW), rightX, rect.bottom - 156f, titleP)
        c.drawText(ellipsised(song.artist, artistP, maxCreditW), rightX, rect.bottom - 114f, artistP)
        if (showLogo) {
            c.drawText("Nevera", rightX, rect.bottom - 72f, brandP)
        }

        drawScrubberBar(c, rect.left + 36f, rect.right - 36f, rect.bottom - 34f, progress)
        c.restore()

        strokePaint.color = Color.argb(66, 255, 255, 255)
        strokePaint.strokeWidth = 2.6f
        c.drawRoundRect(rect, 20f, 20f, strokePaint)
    }

    private fun renderAcrylicOrPosterPlaque(
        line: LyricsShareLine?,
        timing: LyricLine?,
        positionMs: Long?,
        progress: Float,
        isPoster: Boolean,
    ) {
        plaqueBitmap.eraseColor(Color.TRANSPARENT)
        val c = plaqueCanvas
        val pad = 44f
        val cw = plaqueW - pad * 2f
        val ch = plaqueH - pad * 2f
        val rect = RectF(pad, pad, pad + cw, pad + ch)

        paint.color = if (isPoster) Color.argb(236, 18, 15, 20) else Color.argb(214, 34, 28, 34)
        c.drawRoundRect(rect, 24f, 24f, paint)

        c.save()
        val clipPath = Path().apply { addRoundRect(rect, 24f, 24f, Path.Direction.CW) }
        c.clipPath(clipPath)

        if (isPoster) {
            drawSquareCover(c, rect.left, rect.top, cw, 0f)
            paint.shader = LinearGradient(
                0f, rect.top + 180f, 0f, rect.top + 480f,
                intArrayOf(Color.TRANSPARENT, Color.argb(235, 18, 15, 20), Color.rgb(18, 15, 20)),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(rect.left, rect.top + 160f, rect.right, rect.bottom, paint)
            paint.shader = null
        } else {
            drawSquareCover(c, rect.left + 42f, rect.top + 42f, 168f, 12f)
            val tx = rect.left + 236f
            val room = rect.right - 42f - tx
            val tP = fonts.heading(38f, Color.WHITE)
            val aP = fonts.body(27f, Color.argb(175, 236, 232, 224))
            val bP = fonts.body(22f, Color.argb(220, 255, 226, 168), bold = true)
            c.drawText(ellipsised(song.title, tP, room), tx, rect.top + 108f, tP)
            c.drawText(ellipsised(song.artist, aP, room), tx, rect.top + 152f, aP)
            if (showLogo) c.drawText("NEVERA", tx, rect.top + 194f, bP)
        }

        if (line != null) {
            drawPlaqueKaraokeLyrics(
                canvas = c,
                line = line,
                timing = timing,
                positionMs = positionMs,
                left = rect.left + 46f,
                width = cw - 92f,
                centerY = if (isPoster) rect.bottom - 205f else rect.top + 425f,
                centered = isPoster,
                fontSize = if (isPoster) 52f else 56f,
            )
        }

        if (isPoster) {
            val metaP = fonts.body(24f, Color.argb(170, 236, 232, 224)).apply { textAlign = Paint.Align.CENTER }
            val caption = if (showLogo) "${song.title} · ${song.artist} · Nevera" else "${song.title} · ${song.artist}"
            c.drawText(ellipsised(caption, metaP, cw - 90f), rect.centerX(), rect.bottom - 68f, metaP)
        }

        drawScrubberBar(c, rect.left + 44f, rect.right - 44f, rect.bottom - 36f, progress)
        c.restore()

        strokePaint.color = Color.argb(66, 255, 255, 255)
        strokePaint.strokeWidth = 2.6f
        c.drawRoundRect(rect, 24f, 24f, strokePaint)
    }

    private fun drawSpinningDisc(canvas: Canvas, cx: Float, cy: Float, r: Float, spinDeg: Float, isHoloCd: Boolean) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(spinDeg)

        if (!isHoloCd) {
            paint.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(Color.rgb(26, 26, 28), Color.rgb(11, 11, 13), Color.rgb(22, 22, 26), Color.rgb(44, 44, 48)),
                floatArrayOf(0f, 0.62f, 0.94f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(0f, 0f, r, paint)
            paint.shader = null

            strokePaint.color = Color.argb(14, 255, 255, 255)
            strokePaint.strokeWidth = 1.5f
            var gr = r * 0.42f
            while (gr < r * 0.94f) {
                canvas.drawCircle(0f, 0f, gr, strokePaint)
                gr += 8.5f
            }

            val arcRect = RectF(-r * 0.98f, -r * 0.98f, r * 0.98f, r * 0.98f)
            paint.color = Color.argb(38, 255, 255, 255)
            canvas.drawArc(arcRect, -34f, 30f, true, paint)
            canvas.drawArc(arcRect, 146f, 30f, true, paint)

            paint.color = Color.rgb(244, 242, 238)
            canvas.drawCircle(0f, 0f, r * 0.34f, paint)
            paint.color = Color.rgb(34, 33, 38)
            canvas.drawCircle(0f, 0f, r * 0.055f, paint)
        } else {
            paint.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(Color.rgb(235, 232, 242), Color.rgb(194, 192, 210), Color.rgb(154, 150, 174), Color.rgb(232, 228, 242)),
                floatArrayOf(0f, 0.45f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(0f, 0f, r, paint)
            paint.shader = null

            val arcRect = RectF(-r * 0.96f, -r * 0.96f, r * 0.96f, r * 0.96f)
            paint.color = Color.argb(90, 255, 130, 185)
            canvas.drawArc(arcRect, -42f, 28f, true, paint)
            paint.color = Color.argb(92, 125, 228, 255)
            canvas.drawArc(arcRect, -14f, 28f, true, paint)
            paint.color = Color.argb(92, 255, 226, 150)
            canvas.drawArc(arcRect, 138f, 28f, true, paint)
            paint.color = Color.argb(88, 170, 145, 255)
            canvas.drawArc(arcRect, 166f, 28f, true, paint)

            paint.color = Color.argb(145, 22, 20, 28)
            canvas.drawCircle(0f, 0f, r * 0.30f, paint)
            strokePaint.color = Color.argb(115, 255, 255, 255)
            strokePaint.strokeWidth = 2.5f
            canvas.drawCircle(0f, 0f, r * 0.30f, strokePaint)
            paint.color = Color.rgb(18, 16, 22)
            canvas.drawCircle(0f, 0f, r * 0.12f, paint)
        }
        canvas.restore()
    }

    private fun drawSquareCover(canvas: Canvas, x: Float, y: Float, size: Float, radius: Float) {
        val dst = RectF(x, y, x + size, y + size)
        canvas.save()
        if (radius > 0f) {
            canvas.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        }
        if (cover != null && !cover.isRecycled) {
            val sq = minOf(cover.width, cover.height)
            val sx = (cover.width - sq) / 2
            val sy = (cover.height - sq) / 2
            canvas.drawBitmap(cover, Rect(sx, sy, sx + sq, sy + sq), dst, bitmapPaint)
        } else {
            paint.shader = LinearGradient(
                x, y, x + size, y + size,
                Color.rgb(124, 38, 72), Color.rgb(42, 24, 46), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(dst, paint)
            paint.shader = null
        }
        canvas.restore()
        if (radius > 0f) {
            strokePaint.color = Color.argb(46, 255, 255, 255)
            strokePaint.strokeWidth = 2f
            canvas.drawRoundRect(dst, radius, radius, strokePaint)
        }
    }

    private fun drawPlaqueMonospaceLyrics(
        canvas: Canvas,
        line: LyricsShareLine,
        timing: LyricLine?,
        positionMs: Long?,
        x: Float,
        centerY: Float,
        maxW: Float,
    ) {
        val wrapped = cappedRows(wrapLines(line.text, monoPaint, maxW), monoPaint, maxW, 3)
        val lh = 44f
        val startY = centerY - ((wrapped.size - 1) * lh) * 0.5f
        val totalLen = line.text.length.coerceAtLeast(1)
        val revealed = when {
            positionMs == null -> totalLen.toFloat()
            timing != null -> NeveraVocalMotion.smartRevealedChars(timing, positionMs)
            else -> totalLen.toFloat()
        }

        var cursorOffset = 0
        wrapped.forEachIndexed { rowIdx, rowText ->
            val rowStart = line.text.indexOf(rowText, cursorOffset).takeIf { it >= 0 } ?: cursorOffset
            cursorOffset = rowStart + rowText.length
            var cx = x
            val baseline = startY + rowIdx * lh
            // The unsung copy underneath, as the karaoke plaques draw theirs:
            // without it the line's tail was simply invisible until the sweep
            // reached it, so words popped in from nothing instead of lighting up.
            monoPaint.color = Color.argb(96, 246, 241, 234)
            canvas.drawText(rowText, cx, baseline, monoPaint)
            for (k in rowText.indices) {
                val globalIdx = rowStart + k
                val chW = monoPaint.measureText(rowText, k, k + 1)
                val rawT = ((revealed - globalIdx + 0.35f) / 0.85f).coerceIn(0f, 1f)
                val easeT = rawT * rawT * (3f - 2f * rawT)
                if (easeT > 0.01f) {
                    val sc = 0.74f + 0.26f * easeT
                    val dy = (1f - easeT) * 6f
                    canvas.save()
                    canvas.translate(cx + chW * 0.5f, baseline + dy)
                    canvas.scale(sc, sc)
                    monoPaint.color = Color.argb((248 * easeT).roundToInt(), 246, 241, 234)
                    canvas.drawText(rowText, k, k + 1, -chW * 0.5f, 0f, monoPaint)
                    canvas.restore()
                }
                cx += chW
            }
        }
    }

    private fun drawPlaqueKaraokeLyrics(
        canvas: Canvas,
        line: LyricsShareLine,
        timing: LyricLine?,
        positionMs: Long?,
        left: Float,
        width: Float,
        centerY: Float,
        centered: Boolean,
        fontSize: Float,
    ) {
        val textPaint = fonts.heading(fontSize, Color.WHITE)
        val wrapped = cappedRows(wrapLines(line.text, textPaint, width), textPaint, width, 3)
        val lh = fontSize * 1.28f
        val startY = centerY - ((wrapped.size - 1) * lh) * 0.45f
        val totalLen = line.text.length.coerceAtLeast(1)
        val revealed = when {
            positionMs == null -> totalLen.toFloat()
            timing != null -> NeveraVocalMotion.smartRevealedChars(timing, positionMs)
            else -> totalLen.toFloat()
        }

        var cursor = 0
        wrapped.forEachIndexed { rIdx, rowText ->
            val rowStart = line.text.indexOf(rowText, cursor).takeIf { it >= 0 } ?: cursor
            cursor = rowStart + rowText.length
            val rowW = textPaint.measureText(rowText)
            val startX = if (centered) left + (width - rowW) * 0.5f else left
            val y = startY + rIdx * lh

            textPaint.shader = null
            textPaint.color = Color.argb(98, 255, 255, 255)
            canvas.drawText(rowText, startX, y, textPaint)

            val rowRev = (revealed - rowStart).coerceIn(0f, rowText.length.toFloat())
            if (rowRev > 0.01f) {
                val idx = rowRev.toInt().coerceIn(0, rowText.length)
                val edgeW = if (idx >= rowText.length) {
                    rowW
                } else {
                    val h0 = textPaint.measureText(rowText, 0, idx)
                    val h1 = textPaint.measureText(rowText, 0, idx + 1)
                    h0 + (h1 - h0) * (rowRev - idx)
                }
                canvas.save()
                canvas.clipRect(startX - 10f, y - fontSize * 1.3f, startX + edgeW + 10f, y + fontSize * 0.4f)
                textPaint.shader = LinearGradient(
                    startX, y - fontSize, startX + rowW.coerceAtLeast(1f), y,
                    intArrayOf(Color.rgb(255, 253, 248), Color.rgb(255, 226, 168), Color.rgb(255, 186, 164)),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP,
                )
                glowPaint.set(textPaint)
                glowPaint.style = Paint.Style.FILL
                glowPaint.maskFilter = BlurMaskFilter(fontSize * 0.22f, BlurMaskFilter.Blur.NORMAL)
                glowPaint.alpha = 140
                canvas.drawText(rowText, startX, y, glowPaint)
                canvas.drawText(rowText, startX, y, textPaint)
                canvas.restore()
                textPaint.shader = null
            }
        }
    }

    private fun drawScrubberBar(canvas: Canvas, x0: Float, x1: Float, y: Float, progress: Float) {
        val w = (x1 - x0).coerceAtLeast(1f)
        val fillX = x0 + w * progress.coerceIn(0f, 1f)
        paint.shader = null
        paint.color = Color.argb(46, 255, 255, 255)
        canvas.drawRoundRect(x0, y - 3f, x1, y + 3f, 3f, 3f, paint)

        paint.shader = LinearGradient(
            x0, 0f, x1, 0f,
            Color.rgb(184, 59, 94), Color.rgb(245, 164, 134), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(x0, y - 3f, fillX, y + 3f, 3f, 3f, paint)
        paint.shader = null

        glowPaint.style = Paint.Style.FILL
        glowPaint.shader = null
        glowPaint.color = Color.argb(180, 255, 255, 255)
        glowPaint.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(fillX, y, 10f, glowPaint)
        paint.color = Color.WHITE
        canvas.drawCircle(fillX, y, 9.5f, paint)
    }

    /**
     * [wrapped] capped at [maxRows] rows. The cap used to be a plain `take(n)`,
     * which silently deleted the rest of the line — a long lyric just stopped
     * mid-sentence. Now the last kept row is ellipsised, so the cut reads as a
     * cut. (The ellipsised row no longer appears verbatim in the line's text;
     * the row-start lookups in the draw functions already fall back to their
     * running cursor when `indexOf` misses.)
     */
    private fun cappedRows(wrapped: List<String>, p: Paint, maxW: Float, maxRows: Int): List<String> {
        if (wrapped.size <= maxRows) return wrapped
        val kept = wrapped.take(maxRows).toMutableList()
        kept[maxRows - 1] = ellipsised(kept[maxRows - 1], p, maxW)
        return kept
    }

    private fun wrapLines(text: String, p: Paint, maxW: Float): List<String> {
        val src = text.trim()
        if (src.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var rest = src
        while (rest.isNotEmpty()) {
            val fit = p.breakText(rest, true, maxW, null)
            if (fit <= 0) {
                out += rest
                break
            }
            val space = rest.lastIndexOf(' ', fit - 1)
            val take = if (space > 0 && fit < rest.length) space else fit
            if (take <= 0) {
                out += rest
                break
            }
            out += rest.substring(0, take).trimEnd()
            rest = rest.substring(take).trimStart()
        }
        return out.ifEmpty { listOf("") }
    }
}

/**
 * Smooth cosine crossfade envelope [0f..1f] for video/audio fade-in (850 ms)
 * and fade-out (1150 ms).
 */
internal fun shareCrossfadeEnvelope(elapsedMs: Long, totalMs: Long): Float {
    if (totalMs <= 0L) return 1f
    val fadeIn = (elapsedMs.toFloat() / SHARE_CROSSFADE_IN_MS).coerceIn(0f, 1f)
    val fadeOut = ((totalMs - elapsedMs).toFloat() / SHARE_CROSSFADE_OUT_MS).coerceIn(0f, 1f)
    val easeIn = fadeIn * fadeIn * (3f - 2f * fadeIn)
    val easeOut = fadeOut * fadeOut * (3f - 2f * fadeOut)
    return minOf(easeIn, easeOut)
}

internal const val SHARE_CROSSFADE_IN_MS = 850f
internal const val SHARE_CROSSFADE_OUT_MS = 1_150f

/** Supersampling of the Holo Deck / Polaroid card surface: drawn at 2x, shown at ~1x. */
internal const val HOLO_CARD_SS = 2
