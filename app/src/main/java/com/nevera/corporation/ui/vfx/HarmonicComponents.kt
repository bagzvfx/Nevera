package com.nevera.corporation.ui.vfx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevera.corporation.data.model.HarmonicVfxMode
import com.nevera.corporation.ui.i18n.LocalStrings
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch

/**
 * Root-overlay non-consuming fingertip tracker anchored in the exact same coordinate frame
 * as HarmonicFxOverlay so touch glows always appear directly beneath the user's finger.
 */
fun Modifier.harmonicTouchTracker(controller: HarmonicFxController): Modifier =
    this
        .onGloballyPositioned { controller.overlayCoords = it }
        .pointerInput(controller) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val changes = event.changes
                    for (i in changes.indices) {
                        val ch = changes[i]
                        if (ch.changedToDownIgnoreConsumed()) {
                            controller.recordPointerDown(ch.position)
                        }
                    }
                }
            }
        }

/**
 * Scroll-safe tactile spring scale driven cleanly inside graphicsLayer.
 */
fun Modifier.harmonicPress(
    pressedScale: Float = 0.94f,
    interactionSource: MutableInteractionSource
): Modifier = composed {
    val fx = LocalHarmonicFx.current
    if (fx.mode == HarmonicVfxMode.OFF) return@composed this

    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "harmonic_press"
    )

    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Minimalist Play/Pause button reading live LayoutCoordinates at tap time so ripples never drift
 * off-center during transitions or gestures.
 */
@Composable
fun HarmonicPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
    size: Dp = 68.dp,
    modifier: Modifier = Modifier
) {
    val fx = LocalHarmonicFx.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var coordsRef by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "harmonic_play_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .onGloballyPositioned { coordsRef = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color.White)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                fx.emitAcousticWave(fx.resolveEffectCenter(coordsRef))
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.Black,
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

/**
 * Minimalist Like Heart button reading live window coordinates on click.
 */
@Composable
fun HarmonicLikeButton(
    isLiked: Boolean,
    onClick: () -> Unit,
    tint: Color = Color.White,
    modifier: Modifier = Modifier
) {
    val fx = LocalHarmonicFx.current
    val haptic = LocalHapticFeedback.current
    var coordsRef by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val popScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .size(44.dp)
            .onGloballyPositioned { coordsRef = it }
            .graphicsLayer {
                scaleX = popScale.value
                scaleY = popScale.value
            }
            .clip(CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    popScale.snapTo(0.82f)
                    popScale.animateTo(
                        1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                if (!isLiked) {
                    fx.emitBokehLove(fx.resolveEffectCenter(coordsRef))
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = "Like",
            tint = if (isLiked) Color(0xFFFA2D48) else tint,
            modifier = Modifier.size(23.dp)
        )
    }
}

/**
 * Minimalist capsule ThinSlider without thumb knob.
 */
@Composable
fun KineticThinSlider(
    progress: Float,
    onSeekFinished: (Float) -> Unit,
    accentColor: Color,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val fx = LocalHarmonicFx.current
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(progress) }
    var barWidthPx by remember { mutableFloatStateOf(1f) }

    val displayFraction = if (isDragging) dragFraction else progress.coerceIn(0f, 1f)
    val barHeight by animateDpAsState(
        targetValue = if (isDragging) 10.dp else 5.5.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "thin_slider_h"
    )

    val sheenPos = if (isLoading) {
        val infiniteTransition = rememberInfiniteTransition(label = "loading_sheen")
        infiniteTransition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)),
            label = "sheen_x"
        ).value
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .onGloballyPositioned { barWidthPx = it.size.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    dragFraction = (down.position.x / barWidthPx).coerceIn(0f, 1f)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            isDragging = false
                            onSeekFinished(dragFraction)
                            break
                        }
                        if (change.positionChanged()) {
                            dragFraction = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            val h = size.height
            val w = size.width
            val r = CornerRadius(h / 2f, h / 2f)

            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                size = size,
                cornerRadius = r
            )

            val fillW = (w * displayFraction).coerceIn(0f, w)
            if (fillW > 0f) {
                drawRoundRect(
                    color = if (isDragging) Color.White else Color.White.copy(alpha = 0.88f),
                    size = Size(fillW.coerceAtLeast(h), h),
                    cornerRadius = r
                )
            }

            if (isDragging && fx.mode != HarmonicVfxMode.OFF && fillW > 4f) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.35f),
                    center = Offset(fillW, h / 2f),
                    radius = h * 1.9f
                )
            }

            if (isLoading) {
                val startX = (sheenPos - 0.25f) * w
                val endX = (sheenPos + 0.25f) * w
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                        startX = startX,
                        endX = endX
                    ),
                    size = size,
                    cornerRadius = r
                )
            }
        }
    }
}

data class NeveraNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Optimized Floating Frosted Glass Navigation Bar with 0.33x Haze inputScale
 * and live touch glow tracking.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassFloatingNavBar(
    items: List<NeveraNavItem>,
    selectedIndex: Int,
    hazeState: HazeState,
    accentColor: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val pillTabs = items.dropLast(1)
    val searchTabIndex = items.lastIndex
    val searchSelected = selectedIndex == searchTabIndex
    val fx = LocalHarmonicFx.current

    val glassStyle = remember {
        HazeStyle(
            backgroundColor = Color(0xFF141418),
            tints = listOf(HazeTint(Color(0xFF141418).copy(alpha = 0.74f))),
            blurRadius = 22.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color(0xFF16161B).copy(alpha = 0.94f))
        )
    }

    val rimBrush = remember(accentColor) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.20f),
                accentColor.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(60.dp)
                .shadow(12.dp, RoundedCornerShape(30.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(30.dp))
                .hazeEffect(state = hazeState, style = glassStyle) {
                    inputScale = HazeInputScale.Fixed(0.33f)
                }
                .border(0.8.dp, rimBrush, RoundedCornerShape(30.dp))
                .padding(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pillTabs.forEachIndexed { idx, item ->
                    val selected = selectedIndex == idx
                    val tintColor by animateColorAsState(
                        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.56f),
                        label = "tab_tint"
                    )
                    val pillBg by animateColorAsState(
                        targetValue = if (selected) Color.White.copy(alpha = 0.13f) else Color.Transparent,
                        label = "tab_bg"
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    var tabCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { tabCoords = it }
                            .clip(RoundedCornerShape(26.dp))
                            .background(pillBg)
                            .harmonicPress(pressedScale = 0.94f, interactionSource = interactionSource)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                if (System.nanoTime() - fx.lastTouchTimeNanos > 500_000_000L) {
                                    fx.emitTouchGlow(fx.resolveEffectCenter(tabCoords))
                                }
                                onSelect(idx)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = tintColor,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            color = tintColor,
                            fontSize = 10.sp,
                            maxLines = 1,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        val searchInteraction = remember { MutableInteractionSource() }
        var searchCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(
            modifier = Modifier
                .size(60.dp)
                .onGloballyPositioned { searchCoords = it }
                .shadow(12.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .hazeEffect(state = hazeState, style = glassStyle) {
                    inputScale = HazeInputScale.Fixed(0.33f)
                }
                .border(
                    width = if (searchSelected) 1.2.dp else 0.8.dp,
                    brush = if (searchSelected) Brush.linearGradient(listOf(accentColor, Color.White.copy(0.6f))) else rimBrush,
                    shape = CircleShape
                )
                .background(if (searchSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .harmonicPress(pressedScale = 0.90f, interactionSource = searchInteraction)
                .clickable(
                    interactionSource = searchInteraction,
                    indication = null
                ) {
                    if (System.nanoTime() - fx.lastTouchTimeNanos > 500_000_000L) {
                        fx.emitTouchGlow(fx.resolveEffectCenter(searchCoords))
                    }
                    onSelect(searchTabIndex)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = strings.navSearch,
                tint = if (searchSelected) Color.White else Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
