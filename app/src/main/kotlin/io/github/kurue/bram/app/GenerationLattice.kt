package io.github.kurue.bram.app

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.floor

private const val LIFE_COLUMNS = 48
private const val LIFE_ROWS = 96
private const val LIFE_PHASES = 4
private const val LIFE_STEP_MILLIS = 1_600
private const val LIFE_TWEEN_MILLIS = 1_550
private const val LATTICE_STEP_DP = 22
private const val LATTICE_RADIUS_DP = 1.3f

/**
 * A quiet generation signal made from the field's own dots.
 *
 * Each Conway state is rendered into cached layers. During a step, Compose changes only those
 * layers' alpha properties, which the render thread can composite without asking the UI thread to
 * rebuild hundreds of circles on every display frame. Four slightly offset phase groups stop the
 * whole field from blinking as one synchronized block while preserving the underlying Life step.
 */
@Composable
fun GenerationLattice(
    generating: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motionEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
    val life = remember { LifeField(LIFE_COLUMNS, LIFE_ROWS) }
    val progress = remember { Animatable(0f) }
    var frameVersion by remember { mutableIntStateOf(0) }
    val activity by animateFloatAsState(
        targetValue = if (generating) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "generation lattice",
    )

    LaunchedEffect(generating, motionEnabled) {
        if (!generating || !motionEnabled) return@LaunchedEffect
        while (true) {
            life.prepareNext()
            frameVersion++
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(LIFE_TWEEN_MILLIS, easing = LinearEasing),
            )
            life.commit()
            progress.snapTo(0f)
            frameVersion++
            delay((LIFE_STEP_MILLIS - LIFE_TWEEN_MILLIS).toLong())
        }
    }

    val copper = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Box(modifier) {
        repeat(LIFE_PHASES) { phase ->
            LifeLayer(
                life = life,
                future = false,
                phase = phase,
                frameVersion = frameVersion,
                color = copper,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val blend = if (motionEnabled) phaseProgress(progress.value, phase) else 0f
                        alpha = activity * (1f - blend)
                    },
            )
            if (motionEnabled) {
                LifeLayer(
                    life = life,
                    future = true,
                    phase = phase,
                    frameVersion = frameVersion,
                    color = copper,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = activity * phaseProgress(progress.value, phase)
                        },
                )
            }
        }
    }
}

@Composable
private fun LifeLayer(
    life: LifeField,
    future: Boolean,
    phase: Int,
    frameVersion: Int,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier) {
        // Reading the version makes the cached drawing refresh only when a new Life state exists.
        @Suppress("UNUSED_EXPRESSION")
        frameVersion
        val step = LATTICE_STEP_DP.dp.toPx()
        val radius = LATTICE_RADIUS_DP.dp.toPx()
        val visibleColumns = (floor(size.width / step).toInt() + 3).coerceAtMost(LIFE_COLUMNS)
        val visibleRows = (floor(size.height / step).toInt() + 2).coerceAtMost(LIFE_ROWS)
        for (row in 0 until visibleRows) {
            val shift = if (row % 2 == 0) 0f else step / 2f
            val verticalFade = 1f - (row.toFloat() / visibleRows.coerceAtLeast(1)) * 0.35f
            for (column in 0 until visibleColumns) {
                if (cellPhase(row, column) != phase) continue
                val alive = if (future) life.next(row, column) else life.current(row, column)
                if (!alive) continue
                drawCircle(
                    color = color.copy(alpha = 0.18f * verticalFade),
                    radius = radius,
                    center = Offset(column * step + shift, row * step),
                )
            }
        }
    }
}

private fun phaseProgress(progress: Float, phase: Int): Float {
    val delay = phase * 0.06f
    val local = ((progress - delay) / 0.82f).coerceIn(0f, 1f)
    return local * local * (3f - 2f * local)
}

private fun cellPhase(row: Int, column: Int): Int = (row * 7 + column * 3) % LIFE_PHASES

/** A deterministic, wrapping Conway field: alive cells never jump when a device rotates. */
private class LifeField(
    private val columns: Int,
    private val rows: Int,
) {
    private var present = BooleanArray(columns * rows) { index ->
        val mixed = (index * 1103515245 + 12345).ushr(16)
        mixed % 100 < 29
    }
    private var future = BooleanArray(present.size)

    fun current(row: Int, column: Int): Boolean = present[index(row, column)]

    fun next(row: Int, column: Int): Boolean = future[index(row, column)]

    fun prepareNext() {
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                var neighbours = 0
                for (rowOffset in -1..1) {
                    for (columnOffset in -1..1) {
                        if (rowOffset == 0 && columnOffset == 0) continue
                        if (present[index(row + rowOffset, column + columnOffset)]) neighbours++
                    }
                }
                val alive = present[index(row, column)]
                future[index(row, column)] = neighbours == 3 || alive && neighbours == 2
            }
        }
    }

    fun commit() {
        val old = present
        present = future
        future = old
    }

    private fun index(row: Int, column: Int): Int {
        val wrappedRow = (row % rows + rows) % rows
        val wrappedColumn = (column % columns + columns) % columns
        return wrappedRow * columns + wrappedColumn
    }
}
