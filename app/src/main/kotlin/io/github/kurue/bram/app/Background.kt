package io.github.kurue.bram.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.ImageShader
import kotlin.random.Random

/**
 * The surface everything else floats on.
 *
 * Translucent panels only look like glass when there is something behind them to reveal. Against a
 * flat near-black background there is nothing, so the panels read as slightly lighter rectangles
 * regardless of how carefully their edges are lit. This draws a quiet gradient field and a faint
 * grain so the material has something to work against — enough to give the panels depth, not enough
 * to compete with a long reply.
 */
@Composable
fun BramBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.background
    val grain = rememberGrain()

    // Two soft off-screen-centred washes rather than a single linear ramp: a linear gradient reads
    // as a backdrop, while overlapping radial ones read as light in a room.
    val warm = if (dark) Color(0xFF3A2A1E).copy(alpha = 0.55f) else Color(0xFFE8D9C7).copy(alpha = 0.75f)
    val cool = if (dark) Color(0xFF1A2230).copy(alpha = 0.45f) else Color(0xFFDCE3EC).copy(alpha = 0.60f)

    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(warm, Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.08f),
                        radius = size.maxDimension * 0.85f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(cool, Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.78f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
                // Grain last, and very faint: it breaks up the banding a large gradient shows on
                // an OLED panel more than it adds visible texture.
                drawIntoCanvas {
                    drawRect(
                        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)),
                        alpha = if (dark) 0.035f else 0.05f,
                    )
                }
            },
        content = content,
    )
}

/**
 * A small tile of monochrome noise, generated once and repeated.
 *
 * Built in code rather than shipped as an asset so it costs nothing to download and can be resized
 * freely; 128px keeps the repeat invisible at typical densities.
 */
@Composable
private fun rememberGrain(): ImageBitmap = remember {
    val size = 128
    val random = Random(0x8A5A3B)
    val pixels = IntArray(size * size) {
        val value = random.nextInt(120, 190)
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
