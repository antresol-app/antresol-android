package app.antresol.ui.channels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt

/** Width of one minute of the programme guide, set per guide (`Epg.minuteWidth`): short clips are stretched. */
val LocalMinuteWidth = staticCompositionLocalOf { 5.dp }

/** This many milliseconds on the guide's time axis. */
@Composable
fun Long.minutesToDp(): Dp = LocalMinuteWidth.current * (this / 60_000f)

fun Long.minutesToDp(minute: Dp): Dp = minute * (this / 60_000f)

/** Narrowest visible part of a block that still gets its title and time. */
private val MinTextWidth = 48.dp

/**
 * Moves a block's label right by [inset] so it stays at the visible left edge when the programme
 * began off-screen; the same inset in every row (a short remainder shortens the title instead). A
 * sliver (less than [MinTextWidth] of the block left) shows no text rather than «…» over a clipped
 * time. [inset] reads the scroll position and is asked at layout, so scrolling the guide moves the
 * labels without recomposing a block.
 */
fun Modifier.stickyLabel(blockWidth: Density.() -> Float, inset: Density.() -> Float): Modifier = layout { measurable, constraints ->
    val shift = inset()
    val show = blockWidth() - shift >= MinTextWidth.toPx()
    val placeable = measurable.measure(constraints.offset(horizontal = -shift.roundToInt()))
    layout(constraints.constrainWidth(placeable.width + shift.roundToInt()), placeable.height) {
        if (show) placeable.place(shift.roundToInt(), 0)
    }
}
