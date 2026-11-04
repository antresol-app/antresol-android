package app.antresol.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.mobile.R

/** The top of a pushed page: a round glass back button under the status bar. */
@Composable
fun BackBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = PageGutter - 4.dp, vertical = 8.dp),
    ) {
        GlassIconButton(icon = MobileIcons.ChevronLeft, contentDescription = stringResource(R.string.back), onClick = onBack)
    }
}

/** The tab header for tab roots, or the back bar for pushed pages. */
@Composable
fun PageTop(onBack: (() -> Unit)?) {
    if (onBack == null) TabHeader() else BackBar(onBack)
}

/** Lets a child of padded content (a grid with a gutter) span the full width again by [gutter] on each side. */
fun Modifier.fullBleed(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = maxWidth))
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-gutter.roundToPx(), 0) }
}
