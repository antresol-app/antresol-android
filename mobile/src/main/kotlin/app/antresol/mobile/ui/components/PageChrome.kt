package app.antresol.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.mobile.R

/** The top of a pushed page: a round glass back button under the status bar, with optional [actions] on the right. */
@Composable
fun BackBar(onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = PageGutter - 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(icon = MobileIcons.ChevronLeft, contentDescription = stringResource(R.string.back), onClick = onBack)
        Spacer(Modifier.weight(1f))
        actions()
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

/** What a page keeps clear of at its sides in landscape: a camera cutout, a side navigation bar (nothing in portrait). */
val PageSideInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/**
 * The content padding of a page's list whose header draws a backdrop: [PageSideInsets] and [bottom].
 * The header spans the padding again ([fullBleed]) and pads what lies over its backdrop itself.
 */
@Composable
fun pageListPadding(bottom: Dp): PaddingValues {
    val sides = PageSideInsets.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = sides.calculateStartPadding(direction), end = sides.calculateEndPadding(direction), bottom = bottom)
}

/** Lets a backdrop in content padded by [insets] ([pageListPadding]) reach the screen's edges again. */
fun Modifier.fullBleed(insets: WindowInsets): Modifier = layout { measurable, constraints ->
    val left = insets.getLeft(this, layoutDirection)
    val extra = left + insets.getRight(this, layoutDirection)
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = maxWidth))
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-left, 0) }
}
