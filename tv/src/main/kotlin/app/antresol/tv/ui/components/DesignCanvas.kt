package app.antresol.tv.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The TV design canvas: every size in the app is drawn for a 960×540 dp screen (1920×1080 px at 2×). */
val CanvasWidth: Dp = 960.dp
val CanvasHeight: Dp = 540.dp

/**
 * The density that makes the [CanvasWidth]×[CanvasHeight] canvas fit a window of [width]×[height] px
 * (the smaller of the two scales, so the canvas is never smaller than the design, only wider or taller).
 * A TV reports a density of its own choosing (a 1080p one is often tvdpi, 1.33×, which is 1440×810 dp;
 * a 4K one at 2× is 1920×1080 dp), so without this a screen drawn for 960 dp shows its content small
 * in one corner. The font scale of [current] is kept.
 */
fun canvasDensity(width: Int, height: Int, current: Density): Density {
    if (width <= 0 || height <= 0) return current
    val scale = minOf(width / CanvasWidth.value, height / CanvasHeight.value)
    return Density(scale, current.fontScale)
}

/** The window's size in canvas dp (see [DesignCanvas]); the design canvas itself where nothing sets it. */
private val LocalCanvasSize = staticCompositionLocalOf { DpSize(CanvasWidth, CanvasHeight) }

/**
 * Normalises the density once, at the root: [CanvasWidth] dp is the window's width (or its height, on a
 * screen taller than 16:9), whatever density the TV reports. Everything below is sized in canvas dp.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The window's size in px (an unbounded parent, as in a preview, leaves the density alone).
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val density = if (bounded) canvasDensity(constraints.maxWidth, constraints.maxHeight, current) else current
        val size = if (bounded) with(density) { DpSize(constraints.maxWidth.toDp(), constraints.maxHeight.toDp()) } else LocalCanvasSize.current
        CompositionLocalProvider(LocalDensity provides density, LocalCanvasSize provides size, content = content)
    }
}

/**
 * The window's height in canvas dp (at least [CanvasHeight]). Use it instead of `screenHeightDp` of the
 * configuration, which counts in the density the TV reports, not the canvas's.
 */
@Composable
fun canvasHeight(): Dp = LocalCanvasSize.current.height
