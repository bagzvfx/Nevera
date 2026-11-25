package com.music.bitchord.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * The "plop": an element arrives small and transparent, then springs past
 * its resting size and settles — the overshoot is the whole effect, so the
 * spring is under-damped on purpose.
 *
 * Driven by [trigger] rather than by composition: the element sits at rest
 * (and this modifier is a no-op) until [trigger] becomes a new non-zero
 * value, which is what lets a screen that is already composed underneath
 * something — Home under the welcome pages — plop when the cover lifts,
 * and only then. [index] staggers a group: each step waits
 * [PLOP_STAGGER_MS] longer, so a header, then its shelves, land one after
 * another instead of all at once.
 *
 * One-shot by construction: the animation runs in a [LaunchedEffect] keyed
 * on [trigger], so recompositions, scrolls and revisits never replay it.
 */
@Composable
fun Modifier.plopIn(trigger: Int, index: Int = 0): Modifier {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0f)
            delay(index.coerceAtLeast(0) * PLOP_STAGGER_MS)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = PLOP_DAMPING,
                    stiffness = PLOP_STIFFNESS,
                ),
            )
        }
    }
    if (trigger == 0) return this
    return graphicsLayer {
        val p = progress.value
        // Scale rides the spring's own overshoot (the Animatable passes 1f
        // on its way to rest); alpha just fades in over the first stretch.
        val scale = PLOP_START_SCALE + (1f - PLOP_START_SCALE) * p
        scaleX = scale
        scaleY = scale
        alpha = (p * 1.6f).coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
}

private const val PLOP_START_SCALE = 0.7f
private const val PLOP_STAGGER_MS = 70L

/** Well under 1: the bounce past full size is what reads as a plop. */
private const val PLOP_DAMPING = 0.42f
private const val PLOP_STIFFNESS = Spring.StiffnessMediumLow
