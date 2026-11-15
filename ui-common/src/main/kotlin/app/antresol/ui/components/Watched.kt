package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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

/** How a watched card looks: its artwork dimmed (less so while focused), with [WatchedMark] on top. */
object WatchedStyle {
    /** Alpha of a watched card's artwork. */
    const val ARTWORK_ALPHA = 0.45f

    /** Alpha of a watched card's artwork while it has focus (TV), so the picture still reads. */
    const val FOCUSED_ARTWORK_ALPHA = 0.8f

    fun artworkAlpha(watched: Boolean, focused: Boolean = false): Float = when {
        !watched -> 1f
        focused -> FOCUSED_ARTWORK_ALPHA
        else -> ARTWORK_ALPHA
    }
}

/** The "watched" eye in a glass circle, for the top-end corner of a card's artwork. */
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
