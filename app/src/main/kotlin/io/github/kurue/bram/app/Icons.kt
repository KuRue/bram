package io.github.kurue.bram.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs the interface needs, drawn rather than imported.
 *
 * Pulling in the Material icon set for four shapes would add a dependency far larger than the
 * shapes themselves, and drawing them keeps their weight consistent with the rest of the interface
 * instead of inheriting someone else's optical sizing.
 */
@Composable
fun MenuIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.then(Modifier)) {
        val width = size.width * 0.58f
        val left = (size.width - width) / 2f
        val gap = size.height * 0.16f
        val middle = size.height / 2f
        listOf(middle - gap, middle, middle + gap).forEach { y ->
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(left, y),
                end = androidx.compose.ui.geometry.Offset(left + width, y),
                strokeWidth = size.minDimension * 0.075f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** A new, unsaved conversation: a speech bubble with a plus. */
@Composable
fun NewChatIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.075f
        val inset = size.minDimension * 0.22f
        val bubble = androidx.compose.ui.geometry.Rect(
            left = inset,
            top = inset,
            right = size.width - inset,
            bottom = size.height - inset * 1.35f,
        )
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    bubble,
                    androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.18f),
                ),
            )
            // The tail, so it reads as a conversation rather than a rounded box.
            moveTo(bubble.left + bubble.width * 0.26f, bubble.bottom)
            lineTo(bubble.left + bubble.width * 0.22f, size.height - inset * 0.35f)
            lineTo(bubble.left + bubble.width * 0.5f, bubble.bottom)
        }
        drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))

        val centre = bubble.center
        val arm = bubble.width * 0.19f
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(centre.x - arm, centre.y),
            androidx.compose.ui.geometry.Offset(centre.x + arm, centre.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(centre.x, centre.y - arm),
            androidx.compose.ui.geometry.Offset(centre.x, centre.y + arm),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Send: an upward arrow, the shape every messaging app now uses for "commit this". */
@Composable
fun ArrowUpIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        val centreX = size.width / 2f
        val top = size.height * 0.28f
        val bottom = size.height * 0.74f
        val head = size.width * 0.20f

        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(centreX, bottom),
            androidx.compose.ui.geometry.Offset(centreX, top),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(centreX - head, top + head),
            androidx.compose.ui.geometry.Offset(centreX, top),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(centreX + head, top + head),
            androidx.compose.ui.geometry.Offset(centreX, top),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Stop: a rounded square, shown while a run is in flight. */
@Composable
fun StopIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension * 0.34f
        val offset = (size.minDimension - side) / 2f
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(offset, offset),
            size = androidx.compose.ui.geometry.Size(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.22f),
        )
    }
}

/** Load: the play triangle, which everywhere else means "start this". */
@Composable
fun PlayIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val inset = size.minDimension * 0.28f
        val path = Path().apply {
            moveTo(inset, inset)
            lineTo(size.width - inset, size.height / 2f)
            lineTo(inset, size.height - inset)
            close()
        }
        drawPath(path, tint)
    }
}

/** Rename: a pencil, angled the way one is held. */
@Composable
fun PencilIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val inset = size.minDimension * 0.24f
        // The shaft.
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(inset, size.height - inset),
            androidx.compose.ui.geometry.Offset(size.width - inset, inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // The tip, drawn as a short cross-stroke so the shape reads as a pencil rather than a line.
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(inset, size.height - inset),
            androidx.compose.ui.geometry.Offset(inset + stroke * 2f, size.height - inset - stroke * 2f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round,
        )
    }
}
