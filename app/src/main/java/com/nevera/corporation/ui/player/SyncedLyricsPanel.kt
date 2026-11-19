package com.nevera.corporation.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevera.corporation.data.lyrics.LyricsResult
import com.nevera.corporation.data.lyrics.NeveraLyricAiEngine
import com.nevera.corporation.data.model.LyricLine
import com.nevera.corporation.data.model.LyricWord
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.sin

internal data class InstrumentalDotsState(
    val dotProgress: FloatArray,
    val entryAnticipation: Float
)

internal fun computeInstrumentalDotsState(
    posMs: Long,
    startMs: Long,
    endMs: Long
): InstrumentalDotsState {
    val span = (endMs - startMs).coerceAtLeast(800L).toFloat()
    val norm = ((posMs - startMs) / span).coerceIn(0f, 1f)
    val fills = FloatArray(3) { idx ->
        ((norm - idx * 0.27f) / 0.34f).coerceIn(0f, 1f)
    }
    val remainingMs = (endMs - posMs).coerceAtLeast(0L)
    val anticipation = if (remainingMs <= 900L && endMs > startMs) {
        (1f - remainingMs / 900f).coerceIn(0f, 1f)
    } else 0f
    return InstrumentalDotsState(dotProgress = fills, entryAnticipation = anticipation)
}

/**
 * Center-Focused 120fps Karaoke Lyrics Stage:
 * - Keeps active lyric strictly locked in the center of the stage (no manual scrolling & zero blur).
 * - Unbroken full-line TextMeasurer shaping preserves natural kerning & word spacing.
 * - Active characters glow luminously and ride a fluid vertical bounce wave as vocals sweep.
 * - Animated 3-dot instrumental interlude charges progressively and swells cleanly on vocal entry.
 */
@Composable
fun SyncedLyricsPanel(
    lyricsResult: LyricsResult?,
    isLoading: Boolean,
    playheadState: State<PlaybackPlayhead>,
    accentColor: Color = Color.White,
    onSeekToMs: (Long) -> Unit,
    onOpenShareCard: ((List<LyricLine>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val settings by LibraryStore.settings.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val clockMsState = rememberLyricClockMs(playheadState)
    val effectivePosMs = (clockMsState.value + settings.lyricsOffsetMs).coerceAtLeast(0L)

    var showTranslated by remember(lyricsResult) { mutableStateOf(false) }
    var isTranslating by remember(lyricsResult) { mutableStateOf(false) }
    var translatedLines by remember(lyricsResult) { mutableStateOf<List<LyricLine>?>(null) }

    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                Text(strings.lyricsLoading, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
            }
        }
        return
    }

    if (lyricsResult == null || lyricsResult.lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = strings.lyricsUnavailable,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val lines = if (showTranslated && translatedLines != null) translatedLines!! else lyricsResult.lines

    Column(modifier = modifier.fillMaxSize()) {
        // Subtle Karaoke Control Bar: Translate + Quick Sync Offset (no source names shown)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = if (lyricsResult.isSynced) Arrangement.SpaceBetween else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (lyricsResult.isSynced) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "-0.25s",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            LibraryStore.updateSettings { it.copy(lyricsOffsetMs = (it.lyricsOffsetMs - 250L).coerceIn(-4000L, 4000L)) }
                        }
                    )
                    val secOffset = settings.lyricsOffsetMs / 1000f
                    Text(
                        text = if (settings.lyricsOffsetMs == 0L) strings.syncOffsetLabel else "%+.2fs".format(secOffset),
                        color = if (settings.lyricsOffsetMs != 0L) accentColor else Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            LibraryStore.updateSettings { it.copy(lyricsOffsetMs = 0L) }
                        }
                    )
                    Text(
                        text = "+0.25s",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            LibraryStore.updateSettings { it.copy(lyricsOffsetMs = (it.lyricsOffsetMs + 250L).coerceIn(-4000L, 4000L)) }
                        }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Dual-Line toggle (Original + Translated subtitle simultaneously)
                if (showTranslated && translatedLines != null) {
                    val dualOn = settings.dualLineLyrics
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (dualOn) accentColor.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                            .clickable {
                                LibraryStore.updateSettings { it.copy(dualLineLyrics = !it.dualLineLyrics) }
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = strings.dualLineLabel,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Translate chip
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (showTranslated) accentColor.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                        .clickable(enabled = !isTranslating) {
                            if (translatedLines != null) {
                                showTranslated = !showTranslated
                            } else {
                                isTranslating = true
                                scope.launch {
                                    translatedLines = NeveraLyricAiEngine.translateLyrics(
                                        lyricsResult.lines,
                                        settings.appLanguage.code
                                    )
                                    isTranslating = false
                                    showTranslated = true
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.6.dp, color = Color.White)
                    } else {
                        Icon(Icons.Rounded.Translate, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                    Text(
                        text = if (showTranslated) strings.originalLabel else strings.translateLabel,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Share Lyric Card button
                if (onOpenShareCard != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onOpenShareCard(lines) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.Share, strings.shareLyricCardLabel, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        if (!lyricsResult.isSynced) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 24.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(lines, key = { idx, _ -> "unsynced_$idx" }) { _, line ->
                    if (!line.isInstrumental) {
                        Text(
                            text = if (showTranslated && line.translatedText.isNotBlank()) line.translatedText else line.text,
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 20.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            return
        }

        val activeIndex = remember(effectivePosMs, lines) {
            lines.indexOfLast { it.timeMs <= effectivePosMs + 110L }.coerceAtLeast(0)
        }
        val focusEase = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val horizontalPadPx = with(density) { 24.dp.roundToPx() }
            val textWidthPx = (constraints.maxWidth - horizontalPadPx).coerceAtLeast(160)

            AnimatedContent(
                targetState = activeIndex,
                transitionSpec = {
                    val forward = targetState >= initialState
                    val slideIn = slideInVertically(tween(340, easing = focusEase)) { h ->
                        if (forward) h / 5 else -h / 5
                    } + fadeIn(tween(290, easing = focusEase))
                    val slideOut = slideOutVertically(tween(250, easing = focusEase)) { h ->
                        if (forward) -h / 5 else h / 5
                    } + fadeOut(tween(190))
                    slideIn togetherWith slideOut
                },
                label = "center_karaoke_spotlight"
            ) { focusIdx ->
                val activeLine = lines.getOrNull(focusIdx) ?: return@AnimatedContent
                val prevLine = lines.getOrNull(focusIdx - 1)
                val nextLine = lines.getOrNull(focusIdx + 1)
                val nextNextLine = lines.getOrNull(focusIdx + 2)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Previous context line (dimmed cleanly, zero blur)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (prevLine != null && !prevLine.isInstrumental) {
                            val prevText = if (showTranslated && prevLine.translatedText.isNotBlank()) prevLine.translatedText else prevLine.text
                            Text(
                                text = prevText,
                                color = Color.White.copy(alpha = 0.28f),
                                fontSize = 17.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSeekToMs(prevLine.timeMs)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // Active Spotlight Center Line
                    if (activeLine.isInstrumental) {
                        AnimatedInstrumentalDots(
                            posMs = effectivePosMs,
                            startMs = activeLine.timeMs,
                            endMs = activeLine.endTimeMs,
                            accentColor = accentColor,
                            onClick = { onSeekToMs(activeLine.timeMs) }
                        )
                    } else {
                        val dualSubActive = showTranslated && settings.dualLineLyrics && activeLine.translatedText.isNotBlank()
                        val activeText = if (showTranslated && !dualSubActive && activeLine.translatedText.isNotBlank()) {
                            activeLine.translatedText
                        } else {
                            activeLine.text
                        }
                        val revealedFraction = computeLineRevealedFraction(
                            posMs = effectivePosMs,
                            line = activeLine,
                            renderedText = activeText,
                            isActive = true,
                            isPassed = false
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            KaraokeGlowBounceText(
                                text = activeText,
                                revealedFraction = revealedFraction,
                                maxWidthPx = textWidthPx,
                                fontSizeSp = 25,
                                lineHeightSp = 35,
                                baseColor = Color.White.copy(alpha = 0.34f),
                                litColor = Color.White,
                                accentColor = accentColor,
                                fontWeight = FontWeight.Bold
                            )

                            if (activeLine.romajiText.isNotBlank() && (!showTranslated || dualSubActive)) {
                                Spacer(modifier = Modifier.height(6.dp))
                                KaraokeGlowBounceText(
                                    text = activeLine.romajiText,
                                    revealedFraction = revealedFraction,
                                    maxWidthPx = textWidthPx,
                                    fontSizeSp = 15,
                                    lineHeightSp = 21,
                                    baseColor = Color.White.copy(alpha = 0.36f),
                                    litColor = accentColor.copy(alpha = 0.98f),
                                    accentColor = accentColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (dualSubActive) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = activeLine.translatedText,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // Upcoming lines (clean fade depth, no manual scroll & zero blur)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (nextLine != null) {
                            if (nextLine.isInstrumental) {
                                Text(
                                    text = "•  •  •",
                                    color = Color.White.copy(alpha = 0.25f),
                                    fontSize = 18.sp,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                val nextText = if (showTranslated && nextLine.translatedText.isNotBlank()) nextLine.translatedText else nextLine.text
                                Text(
                                    text = nextText,
                                    color = Color.White.copy(alpha = 0.38f),
                                    fontSize = 18.sp,
                                    lineHeight = 24.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSeekToMs(nextLine.timeMs)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (nextNextLine != null && !nextNextLine.isInstrumental) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val nnText = if (showTranslated && nextNextLine.translatedText.isNotBlank()) nextNextLine.translatedText else nextNextLine.text
                        Text(
                            text = nnText,
                            color = Color.White.copy(alpha = 0.18f),
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSeekToMs(nextNextLine.timeMs)
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class ShapedCluster(
    val start: Int,
    val end: Int,
    val x: Float,
    val y: Float,
    val layout: TextLayoutResult,
    val isBlank: Boolean
)

/**
 * Unbroken full-line TextMeasurer renderer applying luminous Glow + Bounce Wave to active
 * karaoke letters/syllables without EVER disturbing word or character spacing.
 */
@Composable
private fun KaraokeGlowBounceText(
    text: String,
    revealedFraction: Float,
    maxWidthPx: Int,
    fontSizeSp: Int,
    lineHeightSp: Int,
    baseColor: Color,
    litColor: Color,
    accentColor: Color,
    fontWeight: FontWeight = FontWeight.Bold
) {
    if (text.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val verticalPadPx = with(density) { 8.dp.toPx() }
    val maxBouncePx = with(density) { 4.2.dp.toPx() }

    val fullStyle = remember(fontSizeSp, lineHeightSp, fontWeight) {
        TextStyle(
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center
        )
    }
    val glyphStyle = remember(fontSizeSp, lineHeightSp, fontWeight) {
        TextStyle(
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            fontWeight = fontWeight,
            textAlign = TextAlign.Unspecified
        )
    }

    val shaped = remember(text, maxWidthPx, fontSizeSp, fontWeight) {
        val fullLayout = textMeasurer.measure(
            text = AnnotatedString(text),
            style = fullStyle,
            constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx)
        )
        val list = mutableListOf<ShapedCluster>()
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        var start = iter.first()
        var end = iter.next()
        while (end != BreakIterator.DONE && list.size < 180) {
            val sub = text.substring(start, end)
            val blank = sub.isBlank()
            val vLine = fullLayout.getLineForOffset(start.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
            val lineTop = fullLayout.getLineTop(vLine)
            val xPos = fullLayout.getHorizontalPosition(start, usePrimaryDirection = true)
            val glyphLayout = if (!blank) {
                textMeasurer.measure(text = AnnotatedString(sub), style = glyphStyle)
            } else fullLayout
            list.add(ShapedCluster(start, end, xPos, lineTop, glyphLayout, blank))
            start = end
            end = iter.next()
        }
        fullLayout to list
    }

    val fullLayout = shaped.first
    val clusters = shaped.second
    val totalHeightDp = with(density) { (fullLayout.size.height.toFloat() + verticalPadPx * 2f).toDp() }
    val widthDp = with(density) { maxWidthPx.toDp() }
    val exactCharPos = (revealedFraction * text.length).coerceIn(0f, text.length.toFloat())

    Canvas(
        modifier = Modifier
            .width(widthDp)
            .height(totalHeightDp)
    ) {
        if (clusters.isEmpty() || clusters.size >= 180) {
            val glow = Shadow(color = accentColor.copy(alpha = 0.65f * revealedFraction), blurRadius = 18f)
            drawText(
                textLayoutResult = fullLayout,
                color = lerp(baseColor, litColor, revealedFraction.coerceIn(0f, 1f)),
                topLeft = Offset(0f, verticalPadPx),
                shadow = if (revealedFraction > 0.1f) glow else null
            )
            return@Canvas
        }

        val waveWindow = 3.4f
        for (i in clusters.indices) {
            val c = clusters[i]
            if (c.isBlank) continue

            val dist = exactCharPos - c.start
            val lit = ((dist + 0.65f) / 1.35f).coerceIn(0f, 1f)
            val waveBell = if (dist in 0f..waveWindow) {
                sin((dist / waveWindow) * PI).toFloat()
            } else 0f
            val dy = -maxBouncePx * waveBell
            val color = lerp(baseColor, litColor, lit)

            val glowStrength = if (lit > 0.05f) {
                (0.32f + 0.68f * waveBell).coerceIn(0.32f, 1f) * lit
            } else 0f
            val shadow = if (glowStrength > 0.04f) {
                Shadow(
                    color = accentColor.copy(alpha = 0.78f * glowStrength),
                    blurRadius = 18f + waveBell * 12f
                )
            } else null

            drawText(
                textLayoutResult = c.layout,
                color = color,
                topLeft = Offset(c.x, c.y + verticalPadPx + dy),
                shadow = shadow
            )
        }
    }
}

/**
 * Animated 3-dot instrumental break indicator reacting in real-time to approaching vocal entry.
 */
@Composable
private fun AnimatedInstrumentalDots(
    posMs: Long,
    startMs: Long,
    endMs: Long,
    accentColor: Color,
    onClick: () -> Unit
) {
    val phaseState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(startMs, endMs) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val dt = ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                    phaseState.floatValue += dt * 4.4f
                }
                lastNanos = nanos
            }
        }
    }

    val dotsState = computeInstrumentalDotsState(posMs, startMs, endMs)
    val cueScale by animateFloatAsState(
        targetValue = 1f + dotsState.entryAnticipation * 0.22f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "inst_entry_cue"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .graphicsLayer {
                scaleX = cueScale
                scaleY = cueScale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 76.dp, height = 28.dp)) {
            val unusedPhase = phaseState.floatValue
            val cy = size.height / 2f
            val spacing = size.width / 3.4f
            val startX = (size.width - spacing * 2f) / 2f
            val bobAmp = 4.5.dp.toPx()

            for (idx in 0 until 3) {
                val fill = dotsState.dotProgress[idx]
                val wave = sin((unusedPhase - idx * 0.9f).toDouble()).toFloat()
                val dy = wave * bobAmp * (0.65f + dotsState.entryAnticipation * 0.45f)
                val cx = startX + idx * spacing
                val baseR = 4.2.dp.toPx() + fill * 1.5.dp.toPx() + dotsState.entryAnticipation * 1.1.dp.toPx()
                val glowAlpha = (0.14f + fill * 0.42f + dotsState.entryAnticipation * 0.30f).coerceIn(0f, 0.78f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(cx, cy + dy),
                        radius = baseR * 2.6f
                    ),
                    center = Offset(cx, cy + dy),
                    radius = baseR * 2.6f
                )
                drawCircle(
                    color = lerp(Color.White.copy(alpha = 0.28f), Color.White, (fill * 0.85f + dotsState.entryAnticipation * 0.4f).coerceIn(0f, 1f)),
                    center = Offset(cx, cy + dy),
                    radius = baseR
                )
            }
        }
    }
}

/**
 * Computes continuous normalized [0f..1f] reveal progress across `renderedText` mapped cleanly
 * against actual character indices (including spaces) so word/letter animation never drifts.
 */
internal fun computeLineRevealedFraction(
    posMs: Long,
    line: LyricLine,
    renderedText: String,
    isActive: Boolean,
    isPassed: Boolean
): Float {
    if (renderedText.isEmpty()) return 1f
    if (isPassed || (posMs >= line.endTimeMs && line.endTimeMs > line.timeMs)) return 1f
    if (!isActive && posMs < line.timeMs) return 0f

    val words: List<LyricWord> = line.words
    if (words.isEmpty() || renderedText != line.text) {
        val span = (line.endTimeMs - line.timeMs).coerceAtLeast(1000L).toFloat()
        return ((posMs - line.timeMs) / span).coerceIn(0f, 1f)
    }

    val totalLen = renderedText.length.toFloat()
    var exactCharPos = 0f
    var scanCursor = 0

    for (w in words) {
        val wStartIdx = if (w.charStart in 0 until renderedText.length) {
            w.charStart
        } else {
            val found = renderedText.indexOf(w.text, startIndex = scanCursor)
            if (found >= 0) found else scanCursor.coerceAtMost(renderedText.length)
        }
        val wEndIdx = if (w.charEnd in (wStartIdx + 1)..renderedText.length) {
            w.charEnd
        } else {
            (wStartIdx + w.text.length).coerceAtMost(renderedText.length)
        }
        scanCursor = wEndIdx

        when {
            posMs >= w.endMs -> exactCharPos = wEndIdx.toFloat()
            posMs <= w.startMs -> {
                if (exactCharPos < wStartIdx.toFloat()) {
                    exactCharPos = wStartIdx.toFloat()
                }
                break
            }
            else -> {
                val wSpan = (w.endMs - w.startMs).coerceAtLeast(60L).toFloat()
                val frac = ((posMs - w.startMs) / wSpan).coerceIn(0f, 1f)
                exactCharPos = wStartIdx + (wEndIdx - wStartIdx) * frac
                break
            }
        }
    }
    return (exactCharPos / totalLen).coerceIn(0f, 1f)
}
