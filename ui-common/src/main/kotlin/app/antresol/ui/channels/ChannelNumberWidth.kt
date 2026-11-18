package app.antresol.ui.channels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/** The widest of [numbers] as text: the largest one (they are never negative), "0" for none. */
fun widestChannelNumber(numbers: Iterable<Int>): String = (numbers.maxOrNull() ?: 0).coerceAtLeast(0).toString()

/**
 * The width of a list's channel-number column: the widest number of the list ([numbers]) measured
 * in the column's [style] with tabular figures, so every number fits on one line («1400» as well
 * as «7») and the names after it line up. The style the column draws in must also use "tnum".
 */
@Composable
fun channelNumberWidth(numbers: Iterable<Int>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val density = LocalDensity.current
    val widest = widestChannelNumber(numbers)
    val tabular = style.copy(fontFeatureSettings = "tnum")
    return remember(widest, tabular, density) {
        val px = measurer.measure(widest, tabular, maxLines = 1, softWrap = false).size.width
        with(density) { px.toDp() }
    }
}
