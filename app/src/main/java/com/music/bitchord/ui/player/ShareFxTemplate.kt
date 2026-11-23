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
import com.music.bitchord.data.lyrics.NeveraVocalMotion
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.replay.Fonts
import com.music.bitchord.ui.replay.ellipsised
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The 5 approved Nevera Share Lyric FX templates selectable in [AndroidLyricsShareSheet].
 */
internal enum class ShareFxTemplate(val label: String) {
    VINYL_PLAQUE("3D Vinyl"),
    HOLO_CD("3D Holo CD"),
    ACRYLIC_GLASS("3D Glass"),
    CINEMA_POSTER("3D Poster"),
    CLEAN_STORY("Clean Story"),
}

/**
 * Software-canvas 3D Floating Music Plaque & Atmospheric Particle Renderer used identically
 * for both the live preview in [AndroidLyricsShareSheet] and Media3 MP4 export in
 * [LyricsShareVideo]. Uses genuine [android.graphics.Camera] 3D perspective transforms!
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

    // Offscreen plaque surface projected into 3D space via Camera + Matrix
    private val plaqueW = 920
    private val plaqueH = 800
    private val plaqueBitmap = Bitmap.createBitmap(plaqueW, plaqueH, Bitmap.Config.ARGB_8888)
    private val plaqueCanvas = Canvas(plaqueBitmap)

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

    // 36 deterministic 3D snow/dust/ash depth flakes (far, mid crisp, close blurred foreground!)
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
        val depth = i % 3 // 0 = far, 1 = mid crisp, 2 = foreground blurred
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

        // 1. Atmospheric dark cinema backdrop (or transparent over Canvas video)
        if (!transparentBackdrop) {
            canvas.drawBitmap(baseBackdrop, 0f, 0f, bitmapPaint)
            // Moody cinema dark veil so the floating 3D plaque pops dramatically
            paint.shader = RadialGradient(
                w * 0.5f, h * 0.48f, w * 0.85f,
                intArrayOf(Color.argb(65, 16, 12, 16), Color.argb(195, 7, 7, 9)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // 2. Far & mid depth snow/dust particles BEHIND the 3D plaque
        drawFlakes(canvas, w, h, tSec, foreground = false)

        // 3. Render current template's crisp card face into plaqueBitmap
        val activeLine = lines.getOrNull(activeIndex.coerceAtLeast(0))
            ?: lines.firstOrNull { !it.isGap }
        val progress = if (totalMs > 0L) (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0.42f

        when (template) {
            ShareFxTemplate.VINYL_PLAQUE -> renderVinylOrCdPlaque(activeLine, positionMs, tSec, progress, isHoloCd = false)
            ShareFxTemplate.HOLO_CD -> renderVinylOrCdPlaque(activeLine, positionMs, tSec, progress, isHoloCd = true)
            ShareFxTemplate.ACRYLIC_GLASS -> renderAcrylicOrPosterPlaque(activeLine, positionMs, progress, isPoster = false)
            ShareFxTemplate.CINEMA_POSTER -> renderAcrylicOrPosterPlaque(activeLine, positionMs, progress, isPoster = true)
            ShareFxTemplate.CLEAN_STORY -> Unit
        }

        // 4. Project plaqueBitmap in true 3D perspective using android.graphics.Camera!
        val rotYDeg = (sin(tSec * 0.68) * 13.5 - 4.5).toFloat()
        val rotXDeg = (cos(tSec * 0.54) * 6.5 + 2.5).toFloat()
        val rotZDeg = (sin(tSec * 0.42) * 1.6).toFloat()
        val hoverY = (sin(tSec * 1.15) * 16.0).toFloat()
        val fitScale = ((w * 0.91f) / plaqueW).coerceAtMost((h * 0.72f) / plaqueH)

        // Ambient shadow floating underneath the 3D card
        canvas.save()
        canvas.translate(w * 0.5f, h * 0.50f + hoverY + 42f)
        canvas.scale(fitScale, fitScale)
        canvas.drawRoundRect(
            -plaqueW * 0.43f,
            -plaqueH * 0.41f,
            plaqueW * 0.43f,
            plaqueH * 0.41f,
            24f,
            24f,
            shadowPaint,
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

        // 5. Large softly blurred foreground snow/dust particles drifting IN FRONT of the 3D plaque!
        drawFlakes(canvas, w, h, tSec, foreground = true)
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

        // Smoked translucent glass body
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

        // Subtle glass specular highlight across upper diagonal
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.left + cw * 0.85f, rect.top + ch * 0.65f,
            Color.argb(28, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rect, 20f, 20f, paint)
        paint.shader = null

        // Clip inside plaque
        c.save()
        val clipPath = Path().apply { addRoundRect(rect, 20f, 20f, Path.Direction.CW) }
        c.clipPath(clipPath)

        // 1. Spinning glossy black Vinyl OR Holographic CD peeking right behind the cover sleeve
        val sleeveX = rect.left + 34f
        val sleeveY = rect.top + 34f
        val sleeveSize = 428f
        val discR = 198f
        val discCx = sleeveX + sleeveSize + 92f
        val discCy = sleeveY + sleeveSize * 0.5f
        val spinDeg = (tSec * 82f) % 360f
        drawSpinningDisc(c, discCx, discCy, discR, spinDeg, isHoloCd)

        // 2. Square Album Sleeve overlapping the left side of the spinning disc
        drawSquareCover(c, sleeveX, sleeveY, sleeveSize, 8f)

        // 3. Minimal Nevera emblem top-right
        if (showLogo) {
            val badgeCx = rect.right - 44f
            val badgeCy = rect.top + 44f
            paint.color = Color.argb(232, 248, 245, 240)
            c.drawCircle(badgeCx, badgeCy, 22f, paint)
            val markPaint = fonts.heading(22f, Color.rgb(22, 20, 24)).apply { textAlign = Paint.Align.CENTER }
            c.drawText("N", badgeCx, badgeCy + 8f, markPaint)
        }

        // 4. Bottom-Left: Monospace Typewriter/Karaoke Lyrics (exact Zexos look!)
        if (line != null) {
            drawPlaqueMonospaceLyrics(
                canvas = c,
                line = line,
                positionMs = positionMs,
                x = rect.left + 36f,
                centerY = rect.bottom - 134f,
                maxW = 440f,
            )
        }

        // 5. Bottom-Right: Song Title, Artist & Nevera
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

        // 6. Live glowing progress scrubber bar along bottom edge
        drawScrubberBar(c, rect.left + 36f, rect.right - 36f, rect.bottom - 34f, progress)

        c.restore()

        // Crisp metallic glass rim
        strokePaint.color = Color.argb(66, 255, 255, 255)
        strokePaint.strokeWidth = 2.6f
        c.drawRoundRect(rect, 20f, 20f, strokePaint)
    }

    private fun renderAcrylicOrPosterPlaque(
        line: LyricsShareLine?,
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
            // Top full-bleed cover fading seamlessly into velvet dark lower panel
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
            // Top album header inside translucent acrylic glass card
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

        // Center silky pearl-gold liquid karaoke lyrics
        if (line != null) {
            drawPlaqueKaraokeLyrics(
                canvas = c,
                line = line,
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

            // Fine vinyl grooves
            strokePaint.color = Color.argb(14, 255, 255, 255)
            strokePaint.strokeWidth = 1.5f
            var gr = r * 0.42f
            while (gr < r * 0.94f) {
                canvas.drawCircle(0f, 0f, gr, strokePaint)
                gr += 8.5f
            }

            // Glossy diagonal specular wedges
            val arcRect = RectF(-r * 0.98f, -r * 0.98f, r * 0.98f, r * 0.98f)
            paint.color = Color.argb(38, 255, 255, 255)
            canvas.drawArc(arcRect, -34f, 30f, true, paint)
            canvas.drawArc(arcRect, 146f, 30f, true, paint)

            // Crisp white vinyl center label + spindle hole
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
        positionMs: Long?,
        x: Float,
        centerY: Float,
        maxW: Float,
    ) {
        val wrapped = wrapLines(line.text, monoPaint, maxW).take(3)
        val lh = 44f
        val startY = centerY - ((wrapped.size - 1) * lh) * 0.5f
        val timing = line.timing
        val totalLen = line.text.length.coerceAtLeast(1)
        val revealed = when {
            positionMs == null -> totalLen.toFloat()
            timing != null && timing.isWordSynced -> NeveraVocalMotion.smartRevealedChars(timing, positionMs)
            else -> {
                val s = line.startMs ?: timing?.timeMs ?: 0L
                val e = line.endMs ?: (s + 3_000L)
                val t = ((positionMs - s).toFloat() / (e - s).coerceAtLeast(1L)).coerceIn(0f, 1f)
                t * totalLen
            }
        }

        var cursorOffset = 0
        wrapped.forEachIndexed { rowIdx, rowText ->
            val rowStart = line.text.indexOf(rowText, cursorOffset).takeIf { it >= 0 } ?: cursorOffset
            cursorOffset = rowStart + rowText.length
            var cx = x
            val baseline = startY + rowIdx * lh
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
        positionMs: Long?,
        left: Float,
        width: Float,
        centerY: Float,
        centered: Boolean,
        fontSize: Float,
    ) {
        val textPaint = fonts.heading(fontSize, Color.WHITE)
        val wrapped = wrapLines(line.text, textPaint, width).take(3)
        val lh = fontSize * 1.28f
        val startY = centerY - ((wrapped.size - 1) * lh) * 0.45f
        val timing = line.timing
        val totalLen = line.text.length.coerceAtLeast(1)
        val revealed = when {
            positionMs == null -> totalLen.toFloat()
            timing != null && timing.isWordSynced -> NeveraVocalMotion.smartRevealedChars(timing, positionMs)
            else -> {
                val s = line.startMs ?: timing?.timeMs ?: 0L
                val e = line.endMs ?: (s + 3_000L)
                (((positionMs - s).toFloat() / (e - s).coerceAtLeast(1L)).coerceIn(0f, 1f)) * totalLen
            }
        }

        var cursor = 0
        wrapped.forEachIndexed { rIdx, rowText ->
            val rowStart = line.text.indexOf(rowText, cursor).takeIf { it >= 0 } ?: cursor
            cursor = rowStart + rowText.length
            val rowW = textPaint.measureText(rowText)
            val startX = if (centered) left + (width - rowW) * 0.5f else left
            val y = startY + rIdx * lh

            // Unsung dim copy
            textPaint.shader = null
            textPaint.color = Color.argb(98, 255, 255, 255)
            canvas.drawText(rowText, startX, y, textPaint)

            // Lit liquid pearl-gold karaoke copy
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

        glowPaint.shader = null
        glowPaint.color = Color.argb(180, 255, 255, 255)
        glowPaint.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(fillX, y, 10f, glowPaint)
        paint.color = Color.WHITE
        canvas.drawCircle(fillX, y, 9.5f, paint)
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
