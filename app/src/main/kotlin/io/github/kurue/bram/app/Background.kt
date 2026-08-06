package io.github.kurue.bram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * The field everything else floats on.
 *
 * A dark ramp with a fine lattice of dots over it. The dots carry the *inverse* of the ramp — bright
 * where the ground is darkest, fading as the ground lifts — so the texture stays visible across the
 * whole screen instead of disappearing into whichever end is closest to its own colour. Rows are
 * offset by half a step, which reads as a diagonal weave rather than as graph paper.
 *
 * Translucent panels need something behind them to reveal; this is that something, and it is kept
 * quiet enough not to compete with a long reply sitting on top of it.
 */
@Composable
fun BramBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.background

    // Near-black at the top lifting to a soft grey, so the ramp has somewhere to go.
    val ramp = if (dark) {
        listOf(Color(0xFF07070A), Color(0xFF141419), Color(0xFF1E1E25))
    } else {
        listOf(Color(0xFFFFFFFF), Color(0xFFF4F1EC), Color(0xFFE8E3DA))
    }
    val dotBright = if (dark) Color.White else Color(0xFF2A2A31)

    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                drawRect(Brush.verticalGradient(ramp))

                val step = 22.dp.toPx()
                val radius = 1.1.dp.toPx()
                if (step <= 0f) return@drawBehind

                // One pass, batched: a per-dot draw call across a phone screen is thousands of
                // calls a frame, while drawPoints hands the whole lattice over at once.
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
                    // Inverse of the ramp: strongest where the ground is darkest.
                    brush = Brush.verticalGradient(
                        listOf(
                            dotBright.copy(alpha = if (dark) 0.20f else 0.10f),
                            dotBright.copy(alpha = if (dark) 0.09f else 0.05f),
                            dotBright.copy(alpha = if (dark) 0.03f else 0.02f),
                        ),
                    ),
                    strokeWidth = radius * 2f,
                    cap = StrokeCap.Round,
                )
            },
        content = content,
    )
}
