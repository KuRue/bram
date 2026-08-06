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
 * The translucent material the interface is built from: a frosted panel that blurs whatever sits
 * behind it. The blur is drawn by [GlassSurface] through the Haze-backed [BackdropBlur]; what lives
 * here is the shared tuning — how opaque each layer is, how soft the blur is, and the corner radii
 * the panels share.
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

    /**
     * Blur radius applied to whatever sits behind a panel. Enough that text passing under one is
     * clearly soft, without erasing it — a panel should read as glass, not as a hole.
     */
    val blurRadius: Dp = 14.dp

    val cornerLarge: Dp = 22.dp
    val cornerMedium: Dp = 16.dp
}

/**
 * A frosted panel.
 *
 * Plain translucency showed the dot lattice behind it pin-sharp, which reads as a tinted hole
 * rather than as glass — what makes glass legible as glass is that detail behind it goes soft. The
 * panel therefore blurs the recorded backdrop behind itself and lays its tint over that in a single
 * layer, rather than a blur with a separate film over it. See [BackdropBlur] for how the recording
 * is made.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Glass.cornerLarge),
    alpha: Float = Glass.CARD_ALPHA,
    tint: Color = Color.Unspecified,
    blurRadius: Dp = Glass.blurRadius,
    /**
     * Whether to blur what is behind. Must be false for anything drawn inside the recorded
     * backdrop — a surface that blurs a recording containing itself recurses until the renderer
     * overflows its stack, which is a hard native crash rather than a visual glitch.
     */
    blur: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = if (tint.isSpecified()) tint else if (dark) Color(0xFF2A2A31) else Color.White

    Box(modifier.clip(shape)) {
        if (blur) {
            // The blur carries the tint, so the panel is one layer rather than a blur with a
            // separate film over it.
            BackdropBlur(blurRadius, base, alpha)
        } else {
            Box(Modifier.matchParentSize().background(base.copy(alpha = alpha)))
        }
        content()
    }
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified
