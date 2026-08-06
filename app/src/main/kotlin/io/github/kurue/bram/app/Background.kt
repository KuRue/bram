package io.github.kurue.bram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/**
 * The field everything else floats on, and the means to sample it behind a panel.
 *
 * Compose cannot blur a backdrop: `Modifier.blur` affects a composable's own content, and reading
 * back what is already on screen needs a third-party library or render-node plumbing. But this
 * background is not arbitrary content — it is a ramp and a lattice that can be evaluated at any
 * coordinate. So a panel does not need to sample the screen; it can redraw the same field at its
 * own position with the lattice softened, which is what frosting a known backdrop actually looks
 * like. [LocalBackdrop] carries the parameters so panels stay aligned to the screen rather than
 * restarting the gradient at their own bounds.
 */
class Backdrop(
    val ramp: List<Color>,
    val dotColor: Color,
    val dotAlphas: List<Float>,
    val screenSize: Size,
) {
    /**
     * Draws the field as it would appear at [origin], with the lattice diffused.
     *
     * [diffusion] widens and dims the dots rather than blurring pixels: a Gaussian over a regular
     * lattice of small points is, to the eye, larger and fainter points, and this costs one draw
     * call instead of an offscreen pass.
     */
    fun DrawScope.drawFrosted(origin: Offset, diffusion: Float) {
        if (screenSize.height <= 0f) return
        // Continue the screen ramp through the panel instead of restarting it, so a panel never
        // reads as a lighter or darker rectangle stamped onto the background.
        drawRect(
            Brush.verticalGradient(
                colors = ramp,
                startY = -origin.y,
                endY = screenSize.height - origin.y,
            ),
        )

        val step = LATTICE_STEP_DP.dp.toPx()
        if (step <= 0f) return
        val radius = LATTICE_RADIUS_DP.dp.toPx() * diffusion

        val firstRow = kotlin.math.floor((origin.y) / step).toInt()
        val points = ArrayList<Offset>()
        var row = firstRow
        var y = row * step - origin.y
        while (y <= size.height + step) {
            val shift = if (row % 2 == 0) 0f else step / 2f
            var x = kotlin.math.floor((origin.x - shift) / step) * step + shift - origin.x
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
            brush = Brush.verticalGradient(
                colors = dotAlphas.map { dotColor.copy(alpha = it / diffusion) },
                startY = -origin.y,
                endY = screenSize.height - origin.y,
            ),
            strokeWidth = radius * 2f,
            cap = StrokeCap.Round,
        )
    }
}

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

private const val LATTICE_STEP_DP = 22
private const val LATTICE_RADIUS_DP = 1.1f

@Composable
fun BramBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.background

    val ramp = if (dark) {
        listOf(Color(0xFF07070A), Color(0xFF141419), Color(0xFF1E1E25))
    } else {
        listOf(Color(0xFFFFFFFF), Color(0xFFF4F1EC), Color(0xFFE8E3DA))
    }
    val dotColor = if (dark) Color.White else Color(0xFF2A2A31)
    // Inverse of the ramp: strongest where the ground is darkest, so the lattice stays visible
    // across the whole screen instead of dissolving into whichever end matches its own colour.
    val dotAlphas = if (dark) listOf(0.20f, 0.09f, 0.03f) else listOf(0.10f, 0.05f, 0.02f)

    var screenSize by remember { mutableStateOf(Size.Zero) }
    val backdrop = Backdrop(ramp, dotColor, dotAlphas, screenSize)

    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .onGloballyPositioned { screenSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
            .drawBehind { with(backdrop) { drawFrosted(Offset.Zero, diffusion = 1f) } },
    ) {
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            content()
        }
    }
}

/** Draws the frosted backdrop for a panel sitting at this position on screen. */
@Composable
fun Modifier.frostedBackdrop(diffusion: Float): Modifier {
    val backdrop = LocalBackdrop.current ?: return this
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind { with(backdrop) { drawFrosted(origin, diffusion) } }
}
