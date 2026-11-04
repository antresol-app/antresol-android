package app.antresol.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * D-pad scrolling that keeps the focused element inside a comfortable zone instead of flush with an
 * edge: [leading] before it (room for a shelf title) and [trailing] after it (room for captions).
 * Nothing scrolls while the focused element is already inside the zone.
 */
@OptIn(ExperimentalFoundationApi::class)
private class ZoneBringIntoViewSpec(
    private val leading: (containerSize: Float) -> Float,
    private val trailing: (containerSize: Float) -> Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = leading(containerSize)
        val end = containerSize - trailing(containerSize)
        val trailingEdge = offset + size
        return when {
            // Taller than the zone: align its start.
            size > end - start -> offset - start
            offset < start -> offset - start
            trailingEdge > end -> trailingEdge - end
            else -> 0f
        }
    }
}

/** Vertical lists of shelves: the focused card keeps [leadingFraction] of the height above it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VerticalShelfScrolling(
    leadingFraction: Float = 0.16f,
    trailingFraction: Float = 0.12f,
    content: @Composable () -> Unit,
) {
    val spec = remember(leadingFraction, trailingFraction) {
        ZoneBringIntoViewSpec({ it * leadingFraction }, { it * trailingFraction })
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Horizontal rows: the focused card stays at least [edge] away from both screen edges. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HorizontalRowScrolling(edge: Dp = ScreenPaddingH, content: @Composable () -> Unit) {
    val edgePx = with(LocalDensity.current) { edge.toPx() }
    val spec = remember(edgePx) { ZoneBringIntoViewSpec({ edgePx }, { edgePx }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
