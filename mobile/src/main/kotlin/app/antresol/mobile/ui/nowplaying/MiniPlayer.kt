package app.antresol.mobile.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.mobile.R
import app.antresol.mobile.playback.PlaybackConnection
import app.antresol.mobile.playback.PlaybackService
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.RoundIconButton
import app.antresol.mobile.ui.components.SkipSecondsButton
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import app.antresol.ui.R as UiR

/** Height the mini-player adds above the tab bar, including its gap. */
val MiniPlayerSpace = 72.dp

/**
 * The glass bar above the tab bar while something plays: artwork, title, artist, play/pause and
 * next (music) or forward 30 s (audiobooks), with a hairline of progress. A tap opens Now Playing.
 */
@Composable
fun MiniPlayer(playback: PlaybackConnection, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val playing = playback.current ?: return
    PositionUpdates(playback)
    val shape = AntresolShapes.control
    Box(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(AntresolColors.BackgroundElevated.copy(alpha = 0.97f))
            .border(1.dp, AntresolColors.GlassBorder, shape)
            .clickable(onClick = onOpen)
            .testTag("mini-player"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArtworkImage(
                url = playing.artworkUrl,
                title = playing.title,
                shape = AntresolShapes.badge,
                modifier = Modifier.size(44.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(playing.title, style = Antresol.typography.bodyEmphasis, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = if (playing.isAudiobook) playback.currentChapter?.title ?: playing.artist else playing.artist
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val paused = !playback.playWhenReady
            RoundIconButton(
                icon = if (paused) AntresolIcons.Play else AntresolIcons.Pause,
                contentDescription = stringResource(if (paused) UiR.string.action_play else UiR.string.player_pause),
                onClick = playback::togglePlayPause,
                size = 44.dp,
                iconSize = 24.dp,
                modifier = Modifier.testTag("mini-play-pause"),
            )
            if (playing.isAudiobook) {
                SkipSecondsButton(
                    seconds = (PlaybackService.SEEK_FORWARD_MS / 1000).toInt(),
                    forward = true,
                    contentDescription = stringResource(R.string.player_skip_forward, PlaybackService.SEEK_FORWARD_MS / 1000),
                    onClick = { playback.skipBy(PlaybackService.SEEK_FORWARD_MS) },
                    size = 44.dp,
                )
            } else {
                RoundIconButton(
                    icon = MobileIcons.SkipNext,
                    contentDescription = stringResource(R.string.now_playing_next),
                    onClick = playback::next,
                    size = 44.dp,
                    iconSize = 22.dp,
                    enabled = playback.currentIndex < playback.queue.lastIndex || playback.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF,
                )
            }
        }
        val fraction = if (playing.isAudiobook) {
            playback.bookPositionMs.toFloat() / playback.bookDurationMs.coerceAtLeast(1)
        } else {
            playback.positionMs.toFloat() / playback.durationMs.coerceAtLeast(1)
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp)
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(2.dp)
                .background(AntresolColors.Label.copy(alpha = 0.8f)),
        )
    }
}

/** Keeps the playback position updating while the calling screen is started (visible). */
@Composable
fun PositionUpdates(playback: PlaybackConnection) {
    LifecycleStartEffect(playback) {
        val release = playback.watchPosition()
        onStopOrDispose { release() }
    }
}
