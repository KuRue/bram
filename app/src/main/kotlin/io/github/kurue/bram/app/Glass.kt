package io.github.kurue.bram.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val chromeAlpha: Float = if (Build.VERSION.SDK_INT >= 31) 0.55f else 0.85f

    /** Cards sit within content, so they can be lighter. */
    const val CARD_ALPHA: Float = 0.34f

    /** Bubbles carry body text; too little opacity and the text behind them competes. */
    const val BUBBLE_ALPHA: Float = 0.40f

    /** Recessed detail (an expanded activity trace) sits behind body text and reads as inset. */
    const val DETAIL_ALPHA: Float = 0.22f

    val cornerLarge: Dp = 22.dp
    val cornerMedium: Dp = 16.dp
}

/**
 * A frosted panel: translucent fill, nothing else.
 *
 * Earlier versions added a sheen gradient and a lit rim to imply thickness. Against a real
 * background those read as drawn-on decoration rather than as material — the eye goes to the
 * outline instead of through the surface. With a field behind it ([BramBackground]) the
 * translucency alone is what makes it look like glass, so that is all this draws.
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
    val base = if (tint.isSpecified()) tint else if (dark) Color(0xFF2A2A31) else Color.White

    Box(
        modifier
            .clip(shape)
            .background(base.copy(alpha = alpha)),
        content = content,
    )
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified
