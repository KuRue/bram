package io.github.kurue.bram.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The translucent material the interface is built from.
 *
 * Compose has no backdrop blur: `Modifier.blur` blurs a composable's own content, not what sits
 * behind it, and true backdrop sampling needs either a third-party library or render-node plumbing
 * that is not worth its cost on a device that may be decoding tokens at the same time. What sells
 * glass visually is mostly not the blur anyway — it is translucency over moving content plus a lit
 * edge, so that is what this draws: a tinted translucent fill, a brighter rim along the top-left
 * where light would catch, and a dimmer one along the bottom-right.
 */
object Glass {
    /** Chrome floats over content and needs more opacity to stay legible while scrolling. */
    val chromeAlpha: Float = if (Build.VERSION.SDK_INT >= 31) 0.82f else 0.94f

    /** Cards sit within content, so they can be lighter. */
    const val CARD_ALPHA: Float = 0.62f

    /** Bubbles carry body text; too little opacity and the text behind them competes. */
    const val BUBBLE_ALPHA: Float = 0.70f

    /** Recessed detail (an expanded activity trace) sits behind body text and reads as inset. */
    const val DETAIL_ALPHA: Float = 0.40f

    val cornerLarge: Dp = 22.dp
    val cornerMedium: Dp = 16.dp
}

/**
 * A translucent panel with a lit rim.
 *
 * [tint] is composited over the theme background rather than used directly, so a translucent panel
 * still reads as part of the surface it sits on instead of as a coloured film.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Glass.cornerLarge),
    alpha: Float = Glass.CARD_ALPHA,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = if (tint.isSpecified()) tint else if (dark) Color(0xFF1B1B20) else Color.White
    val fill = base.copy(alpha = alpha)

    // A single flat rim reads as a plain outline. Two stops — bright where light would land, faint
    // on the opposite edge — is what makes the panel look like it has thickness.
    val rim = Brush.linearGradient(
        colors = if (dark) {
            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))
        } else {
            listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.20f))
        },
    )

    Box(
        modifier
            .clip(shape)
            .background(fill)
            .background(
                Brush.verticalGradient(
                    colors = if (dark) {
                        listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                    } else {
                        listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)
                    },
                ),
            )
            .border(1.dp, rim, shape),
        content = content,
    )
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified
