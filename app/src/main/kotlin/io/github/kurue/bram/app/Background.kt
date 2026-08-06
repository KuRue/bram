package io.github.kurue.bram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Shared state for backdrop blur.
 *
 * Compose has no built-in way to sample what is already on screen behind a composable. Doing it by
 * hand — record the content into a GraphicsLayer, then draw that recording blurred inside each
 * panel — runs into ownership rules that leave a layer recorded by one node painting nothing when
 * sampled from another, and into a native stack overflow if anything inside the recording samples
 * it. Haze handles that lifecycle, so the app supplies only the two ends: the content to blur, and
 * the panels that blur it.
 */
val LocalHaze = compositionLocalOf<HazeState?> { null }

private const val LATTICE_STEP_DP = 22
private const val LATTICE_RADIUS_DP = 1.1f

/** Owns the blur state so anything below it — including the drawer — samples the same frame. */
@Composable
fun BackdropHost(content: @Composable () -> Unit) {
    val hazeState = rememberHazeState()
    CompositionLocalProvider(LocalHaze provides hazeState, content = content)
}

/**
 * Paints the field: a dark ramp with a fine lattice over it.
 *
 * The dots carry the inverse of the ramp — brightest where the ground is darkest — so the texture
 * stays visible across the whole screen instead of dissolving into whichever end matches its own
 * colour. Alternate rows shift by half a step, which reads as a diagonal weave rather than graph
 * paper.
 */
@Composable
fun Modifier.bramField(): Modifier {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.background
    val ramp = if (dark) {
        listOf(Color(0xFF07070A), Color(0xFF141419), Color(0xFF1E1E25))
    } else {
        listOf(Color(0xFFFFFFFF), Color(0xFFF4F1EC), Color(0xFFE8E3DA))
    }
    val dotColor = if (dark) Color.White else Color(0xFF2A2A31)
    val dotAlphas = if (dark) listOf(0.20f, 0.09f, 0.03f) else listOf(0.10f, 0.05f, 0.02f)

    return background(base).drawBehind {
        drawRect(Brush.verticalGradient(ramp))
        val step = LATTICE_STEP_DP.dp.toPx()
        if (step <= 0f) return@drawBehind
        val points = ArrayList<Offset>()
        var row = 0
        var y = 0f
        while (y <= size.height + step) {
            val shift = if (row % 2 == 0) 0f else step / 2f
            var x = -step + shift
            while (x <= size.width + step) {
                points += Offset(x, y)
                x += step
            }
            y += step
            row++
        }
        drawPoints(
            points = points,
            pointMode = PointMode.Points,
            brush = Brush.verticalGradient(dotAlphas.map { dotColor.copy(alpha = it) }),
            strokeWidth = LATTICE_RADIUS_DP.dp.toPx() * 2f,
            cap = StrokeCap.Round,
        )
    }
}

/** Marks this element as the content that panels blur. */
@Composable
fun Modifier.recordBackdrop(): Modifier {
    val hazeState = LocalHaze.current ?: return this
    return hazeSource(hazeState)
}

/**
 * Blurs whatever sits behind this element.
 *
 * Drawn as a child under the caller's content so the content itself stays sharp; blurring the whole
 * element would take its text with it. The tint rides along with the blur, so a panel is one layer
 * rather than a blur with a separate film over it.
 */
@Composable
fun BoxScope.BackdropBlur(radius: Dp, tint: Color, tintAlpha: Float) {
    val hazeState = LocalHaze.current ?: return
    Box(
        Modifier
            .matchParentSize()
            .hazeEffect(state = hazeState) {
                blurRadius = radius
                backgroundColor = tint
                tints = listOf(HazeTint(tint.copy(alpha = tintAlpha)))
                // A little grain stops a large uniform blur banding on an OLED panel.
                noiseFactor = 0.06f
            },
    )
}
