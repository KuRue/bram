package io.github.kurue.bram.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared state for real backdrop blur.
 *
 * [layer] holds a recording of everything that should appear blurred behind a panel — the field and
 * the transcript. A panel draws that recording, shifted so the region under it lands in place, with
 * a blur applied. That is what makes text scrolling behind a panel actually go soft; a stylised
 * redraw of the background cannot, because it knows nothing about the content on top of it.
 *
 * The recording deliberately excludes the panels themselves. A panel that sampled a recording
 * containing itself would blur an image of its own previous frame, which feeds back.
 */
class Backdrop(val layer: GraphicsLayer)

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/** RenderEffect blur arrived in API 31. Below that, panels stay translucent but unblurred. */
private val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

private const val LATTICE_STEP_DP = 22
private const val LATTICE_RADIUS_DP = 1.1f

/** Owns the recording so anything under it — including the drawer — can blur the same frame. */
@Composable
fun BackdropHost(content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    CompositionLocalProvider(LocalBackdrop provides Backdrop(layer), content = content)
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

/** Records this element's drawing as the frame panels will blur. */
@Composable
fun Modifier.recordBackdrop(): Modifier {
    val backdrop = LocalBackdrop.current ?: return this
    return drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }
}

/**
 * Draws the recorded backdrop, blurred, filling its parent.
 *
 * The blur belongs to *this* element's layer, not to the recording. A GraphicsLayer carries one
 * render effect, so setting it on the shared recording blurs every use of it — including the main
 * draw, which smears the whole screen. Giving the effect to a private layer that merely samples the
 * recording keeps the recording itself sharp.
 *
 * Placed under the caller's content so the content stays legible; blurring the whole element would
 * take its text with it.
 */
@Composable
fun BoxScope.BackdropBlur(radius: Dp) {
    val backdrop = LocalBackdrop.current ?: return
    if (!blurSupported) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer {
                renderEffect = BlurEffect(
                    radiusX = radius.toPx(),
                    radiusY = radius.toPx(),
                    edgeTreatment = TileMode.Clamp,
                )
                clip = true
            }
            .drawBehind {
                // Shift the recording so the region sitting under this panel lands beneath it.
                translate(left = -origin.x, top = -origin.y) {
                    drawLayer(backdrop.layer)
                }
            },
    )
}
