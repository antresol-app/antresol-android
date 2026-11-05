package app.antresol.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.core.model.Track
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.format.Durations
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * A song row: number (or cover), title, artist when the list mixes artists, star and length.
 * The playing song is drawn in the accent colour.
 */
@Composable
fun TrackRow(
    track: Track,
    number: Int?,
    showArtist: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = false,
    /** A long press (add to a playlist). */
    onLongClick: (() -> Unit)? = null,
    /** Replaces the duration (playlist editing controls); the row then does not play on tap. */
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val urls = LocalMediaUrls.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, enabled = trailing == null, onLongClick = onLongClick, onClick = onClick)
            .testTag("track-${track.id}")
            .padding(horizontal = PageGutter, vertical = if (showCover) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showCover) {
            ArtworkImage(url = urls?.image(track.cover), title = track.album, shape = AntresolShapes.badge, modifier = Modifier.size(44.dp))
        } else {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                if (playing) {
                    Icon(MobileIcons.Music, contentDescription = null, tint = AntresolColors.Accent, modifier = Modifier.size(16.dp))
                } else if (number != null) {
                    Text(number.toString(), style = Antresol.typography.caption, textAlign = TextAlign.End)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = Antresol.typography.body.copy(color = if (playing) AntresolColors.Accent else AntresolColors.Label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showArtist && track.artist.isNotBlank()) {
                Text(track.artist, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            if (track.starred) Icon(MobileIcons.StarFilled, contentDescription = null, tint = AntresolColors.Orange, modifier = Modifier.size(14.dp))
            Text(Durations.clock(track.durationMs), style = Antresol.typography.caption)
        }
    }
}
