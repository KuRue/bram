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

/** A new conversation: the familiar compose mark, kept spare at this small optical size. */
@Composable
fun NewChatIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.078f
        val inset = size.minDimension * 0.20f
        val boxRight = size.width * 0.72f
        val boxTop = size.height * 0.28f
        val path = Path().apply {
            moveTo(boxRight, boxTop)
            lineTo(boxRight, size.height - inset)
            quadraticTo(boxRight, size.height - inset * 0.75f, boxRight - inset * 0.25f, size.height - inset * 0.75f)
            lineTo(inset * 1.25f, size.height - inset * 0.75f)
            quadraticTo(inset * 0.75f, size.height - inset * 0.75f, inset * 0.75f, size.height - inset * 1.25f)
            lineTo(inset * 0.75f, inset * 1.25f)
            quadraticTo(inset * 0.75f, inset * 0.75f, inset * 1.25f, inset * 0.75f)
            lineTo(size.width - inset * 1.45f, inset * 0.75f)
        }
        drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        val pencilStart = androidx.compose.ui.geometry.Offset(size.width * 0.43f, size.height * 0.59f)
        val pencilEnd = androidx.compose.ui.geometry.Offset(size.width * 0.79f, size.height * 0.23f)
        drawLine(
            tint,
            pencilStart,
            pencilEnd,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            pencilStart,
            androidx.compose.ui.geometry.Offset(pencilStart.x - stroke * 0.65f, pencilStart.y + stroke * 0.65f),
            strokeWidth = stroke * 0.7f,
            cap = StrokeCap.Round,
        )
    }
}

/** Three descending lanes: dim when unrestricted, accented when Android reports heat pressure. */
@Composable
fun ThrottleIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.13f
        val xs = listOf(0.24f, 0.50f, 0.76f)
        val tops = listOf(0.22f, 0.36f, 0.50f)
        xs.zip(tops).forEach { (x, top) ->
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(size.width * x, size.height * top),
                end = androidx.compose.ui.geometry.Offset(size.width * x, size.height * 0.78f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
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

/** Delete: a simple lidded bin, used for destructive swipe actions. */
@Composable
fun TrashIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.075f
        val left = size.width * 0.30f
        val right = size.width * 0.70f
        val top = size.height * 0.34f
        val bottom = size.height * 0.76f
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(left, top),
            androidx.compose.ui.geometry.Offset(right, top),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.27f),
            androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.27f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(size.width * 0.43f, size.height * 0.20f),
            androidx.compose.ui.geometry.Offset(size.width * 0.57f, size.height * 0.20f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val body = Path().apply {
            moveTo(left, top)
            lineTo(left + size.width * 0.04f, bottom)
            lineTo(right - size.width * 0.04f, bottom)
            lineTo(right, top)
        }
        drawPath(body, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}
