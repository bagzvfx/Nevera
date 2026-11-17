package com.nevera.corporation.ui.theme

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class NeveraArtworkColors(
    val primary: Color = Color(0xFF1DB954),
    val secondary: Color = Color(0xFF537FE7),
    val backdropTop: Color = Color(0xFF1E2924),
    val backdropBottom: Color = Color(0xFF0D0D10)
)

private val paletteCache = ConcurrentHashMap<String, NeveraArtworkColors>()

@Composable
fun rememberArtworkColors(imageUrl: String?): NeveraArtworkColors {
    val context = LocalContext.current
    var targetColors by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { paletteCache[it] } ?: NeveraArtworkColors())
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            targetColors = NeveraArtworkColors()
            return@LaunchedEffect
        }
        paletteCache[imageUrl]?.let {
            targetColors = it
            return@LaunchedEffect
        }

        val extracted = withContext(Dispatchers.IO) {
            try {
                val loader = ImageLoader(context)
                val req = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(128)
                    .allowHardware(false)
                    .build()
                val res = loader.execute(req)
                val bmp: Bitmap? = (res as? SuccessResult)?.drawable?.let { d ->
                    (d as? BitmapDrawable)?.bitmap
                }
                if (bmp != null) {
                    val palette = Palette.from(bmp).generate()
                    val vibrant = palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                        ?: 0xFF1DB954.toInt()
                    val muted = palette.mutedSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: 0xFF2B3A4A.toInt()
                    val dark = palette.darkMutedSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: 0xFF181A20.toInt()

                    val primaryColor = ensureVisibleAccent(Color(vibrant))
                    val secondaryColor = ensureVisibleAccent(Color(muted))
                    val topColor = darkenColor(Color(dark), 0.55f)

                    NeveraArtworkColors(
                        primary = primaryColor,
                        secondary = secondaryColor,
                        backdropTop = topColor,
                        backdropBottom = Color(0xFF0A0A0D)
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }

        if (extracted != null) {
            paletteCache[imageUrl] = extracted
            targetColors = extracted
        }
    }

    val primary by animateColorAsState(targetColors.primary, tween(650), label = "pal_pri")
    val secondary by animateColorAsState(targetColors.secondary, tween(650), label = "pal_sec")
    val top by animateColorAsState(targetColors.backdropTop, tween(650), label = "pal_top")
    val bottom by animateColorAsState(targetColors.backdropBottom, tween(650), label = "pal_bot")

    return NeveraArtworkColors(primary, secondary, top, bottom)
}

private fun ensureVisibleAccent(c: Color): Color {
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return if (lum < 0.35f) {
        Color(
            red = (c.red + 0.32f).coerceIn(0f, 1f),
            green = (c.green + 0.32f).coerceIn(0f, 1f),
            blue = (c.blue + 0.32f).coerceIn(0f, 1f),
            alpha = 1f
        )
    } else c
}

private fun darkenColor(c: Color, factor: Float): Color {
    return Color(
        red = (c.red * factor).coerceIn(0f, 1f),
        green = (c.green * factor).coerceIn(0f, 1f),
        blue = (c.blue * factor).coerceIn(0f, 1f),
        alpha = 1f
    )
}
