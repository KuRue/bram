package io.github.kurue.bram.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A warm neutral palette rather than Material's default purple.
 *
 * Long model replies are the main thing on screen, so the interface aims to recede: a paper-like
 * background, one restrained accent for actions and state, and no large saturated fills behind
 * body text.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A3B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E2D6),
    onPrimaryContainer = Color(0xFF3B2313),
    secondary = Color(0xFF6B6259),
    background = Color(0xFFF7F5F2),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFF7F5F2),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFE9E4DD),
    onSurfaceVariant = Color(0xFF5A554E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2EFEA),
    surfaceContainer = Color(0xFFEDE9E3),
    surfaceContainerHigh = Color(0xFFE7E2DB),
    surfaceContainerHighest = Color(0xFFE2DCD4),
    secondaryContainer = Color(0xFFEDE4DA),
    onSecondaryContainer = Color(0xFF3B3229),
    outline = Color(0xFFBCB5AC),
    error = Color(0xFF8F2F2F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD9A47C),
    onPrimary = Color(0xFF3B2313),
    primaryContainer = Color(0xFF4A3222),
    onPrimaryContainer = Color(0xFFF0E2D6),
    secondary = Color(0xFFCFC6BC),
    background = Color(0xFF0E0E11),
    onBackground = Color(0xFFEDEAE5),
    surface = Color(0xFF0E0E11),
    onSurface = Color(0xFFEDEAE5),
    surfaceVariant = Color(0xFF2A2A30),
    onSurfaceVariant = Color(0xFFB5AFA7),
    surfaceContainerLowest = Color(0xFF141418),
    surfaceContainerLow = Color(0xFF1A1A1F),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF26262D),
    surfaceContainerHighest = Color(0xFF2E2E36),
    secondaryContainer = Color(0xFF33302B),
    onSecondaryContainer = Color(0xFFE6DED4),
    outline = Color(0xFF4A4A52),
    error = Color(0xFFE49393),
)

/** Slightly looser than Material's defaults; replies are long and read better with room. */
private val BramTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun BramTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = BramTypography,
        content = content,
    )
}
