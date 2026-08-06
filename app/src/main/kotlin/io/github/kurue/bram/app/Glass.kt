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
import androidx.compose.ui.geometry.Offset
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
    val chromeAlpha: Float = if (Build.VERSION.SDK_INT >= 31) 0.72f else 0.90f

    /** Cards sit within content, so they can be lighter. */
    const val CARD_ALPHA: Float = 0.46f

    /** Bubbles carry body text; too little opacity and the text behind them competes. */
    const val BUBBLE_ALPHA: Float = 0.55f

    /** Recessed detail (an expanded activity trace) sits behind body text and reads as inset. */
    const val DETAIL_ALPHA: Float = 0.30f

    val cornerLarge: Dp = 22.dp
    val cornerMedium: Dp = 16.dp
}

/**
 * A frosted translucent panel.
 *
 * Weighted towards the frost rather than the edge. An earlier version leaned on a bright rim to
 * imply thickness, which reads as a drawn outline rather than as material — especially on a dark
 * panel where the rim is the only thing catching the eye. With a gradient field behind it
 * ([BramBackground]) the translucency itself does the work, so the fill is lighter, a broad sheen
 * falls across the top where light would land, and the edge is reduced to a hairline that defines
 * the shape without announcing itself.
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
    val base = if (tint.isSpecified()) tint else if (dark) Color(0xFF23232A) else Color.White
    val fill = base.copy(alpha = alpha)

    // Diagonal rather than vertical, and reaching about two thirds down: a short vertical fade
    // reads as a header strip, while a longer diagonal one reads as light across a surface.
    val sheen = Brush.linearGradient(
        colors = if (dark) {
            listOf(
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.03f),
                Color.Transparent,
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.18f),
                Color.Transparent,
            )
        },
        start = Offset.Zero,
        end = Offset(0f, Float.POSITIVE_INFINITY),
    )

    val hairline = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.45f)

    Box(
        modifier
            .clip(shape)
            .background(fill)
            .background(sheen)
            .border(0.5.dp, hairline, shape),
        content = content,
    )
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified
