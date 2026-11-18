package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.ui.R
import app.antresol.ui.theme.AntresolColors

/**
 * How a watched card looks: its artwork dimmed (less so while focused), with [WatchedMark] on top,
 * and no progress bar, not even a full one. The same on the web and in the other apps: the artwork
 * at half strength, 0.85 while chosen, the eye in the middle of it a quarter of the tile's width.
 */
object WatchedStyle {
    /** Alpha of a watched card's artwork. */
    const val ARTWORK_ALPHA = 0.5f

    /** Alpha of a watched card's artwork while it has focus (TV), so the picture still reads. */
    const val FOCUSED_ARTWORK_ALPHA = 0.85f

    fun artworkAlpha(watched: Boolean, focused: Boolean = false): Float = when {
        !watched -> 1f
        focused -> FOCUSED_ARTWORK_ALPHA
        else -> ARTWORK_ALPHA
    }

    /** The eye's disc: a quarter of the tile's width, 28 to 52 dp (reads on a big card, does not smother a small one). */
    fun markSize(tileWidth: Dp): Dp = (tileWidth * 0.24f).coerceIn(28.dp, 52.dp)
}

/**
 * [WatchedMark] in the middle of the card's artwork it is placed in (a `Box` with the tile's size),
 * sized by that tile's width. At full strength: the artwork under it is dimmed by [WatchedStyle].
 */
@Composable
fun WatchedMarkOverlay(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WatchedMark(size = WatchedStyle.markSize(maxWidth))
    }
}

/** The "watched" eye in a glass circle (place it with [WatchedMarkOverlay] over a card's artwork). */
@Composable
fun WatchedMark(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(AntresolColors.Glass, CircleShape)
            .border(1.dp, AntresolColors.GlassBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AntresolIcons.Eye,
            contentDescription = stringResource(R.string.detail_watched),
            tint = AntresolColors.Label,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}
