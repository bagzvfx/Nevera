package com.music.bitchord.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppFont

// Nevera editorial vermilion accent.
val AccentRed = Color(0xFFD94A38)

// Dark palette (#0E0E0D archival charcoal, warm white typography)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFF7F5EE),
    onPrimary = Color(0xFF0E0E0D),
    background = Color(0xFF0E0E0D),
    onBackground = Color(0xFFF7F5EE),
    surface = Color(0xFF171715),
    onSurface = Color(0xFFF7F5EE),
    surfaceVariant = Color(0xFF22221F),
    onSurfaceVariant = Color(0xFF9B978C),
    outline = Color(0xFF33322D),
)

// Pure OLED palette (#000000 true black)
private val OledColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0C0C0C),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2A2A2D),
)

// Light palette (#F5F2EB warm archival paper, rich dark ink)
private val LightColors = lightColorScheme(
    primary = Color(0xFF121210),
    onPrimary = Color(0xFFF5F2EB),
    background = Color(0xFFF5F2EB),
    onBackground = Color(0xFF121210),
    surface = Color(0xFFECE8DE),
    onSurface = Color(0xFF121210),
    surfaceVariant = Color(0xFFE2DDD1),
    onSurfaceVariant = Color(0xFF636058),
    outline = Color(0xFFD0CAC0),
)

val NeveraEditorialFont = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.W400),
    Font(R.font.jakarta_medium, FontWeight.W500),
    Font(R.font.jakarta_semibold, FontWeight.W600),
    Font(R.font.jakarta_bold, FontWeight.W700),
    Font(R.font.jakarta_bold, FontWeight.W800),
)

val SFProDisplay = NeveraEditorialFont

private val NeveraTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 38.sp, letterSpacing = (-1.0).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 32.sp, letterSpacing = (-0.75).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 24.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 21.sp, letterSpacing = (-0.35).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 15.5.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 13.5.sp, letterSpacing = 0.05.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 15.5.sp, letterSpacing = 0.05.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 13.5.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp, letterSpacing = 0.35.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 10.5.sp, letterSpacing = 0.55.sp),
).withFamily(NeveraEditorialFont)

val SFProDisplayLegacy = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

private val LegacyTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp),
).withFamily(SFProDisplayLegacy)

private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

@Composable
fun BitChordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureOled: Boolean = false,
    font: AppFont = AppFont.DEFAULT,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when {
            pureOled -> OledColors
            darkTheme -> DarkColors
            else -> LightColors
        },
        typography = when (font) {
            AppFont.DEFAULT -> NeveraTypography
            AppFont.LEGACY -> LegacyTypography
        },
        content = content,
    )
}

@Composable
fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}
