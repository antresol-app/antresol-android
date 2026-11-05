package app.antresol.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.PlayMode
import app.antresol.core.model.Quality
import app.antresol.tv.ui.components.MenuOption
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.ui.R
import app.antresol.ui.format.Durations
import app.antresol.ui.format.TrackLabels
import app.antresol.ui.playback.VideoPlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** The side menus of the player. */
enum class PlayerMenu { Audio, Subtitles, Quality }

/**
 * The focusable progress bar: Left/Right seek (10 s by default), OK toggles pause. It thickens and shows a
 * knob when focused. Elapsed time on the left, remaining time on the right.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    backStepMs: Long = VideoPlayerViewModel.SEEK_STEP_MS,
    forwardStepMs: Long = VideoPlayerViewModel.SEEK_STEP_MS,
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .height(22.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        Key.DirectionLeft -> { if (down) onSeek(-backStepMs); true }
                        Key.DirectionRight -> { if (down) onSeek(forwardStepMs); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (down) onToggle(); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackHeight = if (focused) 8.dp else 5.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(AntresolShapes.pill)
                    .background(AntresolColors.FillPrimary),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(buffered)
                        .fillMaxHeight()
                        .background(AntresolColors.Label.copy(alpha = 0.3f)),
                )
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(if (focused) AntresolColors.Label else AntresolColors.Label.copy(alpha = 0.85f)),
                )
            }
            if (focused) {
                val knob = 18.dp
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction - knob / 2)
                        .size(knob)
                        .background(AntresolColors.Label, CircleShape),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Durations.clock(positionMs), style = Antresol.typography.caption)
            Spacer(Modifier.weight(1f))
            Text("-" + Durations.clock((durationMs - positionMs).coerceAtLeast(0)), style = Antresol.typography.caption)
        }
    }
}

/** The audio, subtitle or quality choices of the player in an [OptionsPanel]. */
@Composable
fun TrackMenu(menu: PlayerMenu, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    val title = stringResource(
        when (menu) {
            PlayerMenu.Audio -> R.string.detail_audio
            PlayerMenu.Subtitles -> R.string.detail_subtitles
            PlayerMenu.Quality -> R.string.detail_quality
        },
    )
    val options = when (menu) {
        PlayerMenu.Audio -> viewModel.audioTracks.map { track ->
            MenuOption(TrackLabels.audio(resources, track), track.index == viewModel.selectedAudioIndex) { viewModel.selectAudio(track) }
        }
        PlayerMenu.Subtitles -> listOf(
            MenuOption(stringResource(R.string.detail_subtitles_off), viewModel.selectedSubtitle == null) { viewModel.selectSubtitle(null) },
        ) + viewModel.subtitleTracks.map { track ->
            MenuOption(TrackLabels.subtitle(resources, track), track.index == viewModel.selectedSubtitle?.index) { viewModel.selectSubtitle(track) }
        }
        PlayerMenu.Quality -> Quality.ALL.map { value ->
            MenuOption(qualityLabel(value), value == viewModel.quality) { viewModel.selectQuality(value) }
        }
    }
    val note = if (menu == PlayerMenu.Quality) streamNote(viewModel) else null
    OptionsPanel(title = title, options = options, onDismiss = onDismiss, note = note)
}

/** "Auto", "Original" or "720p". */
@Composable
fun qualityLabel(value: String): String = when (value) {
    Quality.AUTO -> stringResource(R.string.quality_auto)
    Quality.ORIGINAL -> stringResource(R.string.quality_original)
    else -> "${value}p"
}

/** "Direct Stream · container mkv isn't supported…": how the server delivers the stream and why. */
@Composable
private fun streamNote(viewModel: PlayerViewModel): String? {
    val play = viewModel.source?.play ?: return null
    val mode = stringResource(
        when (play.mode) {
            PlayMode.DIRECT -> R.string.player_mode_direct
            PlayMode.REMUX -> R.string.player_mode_remux
            else -> R.string.player_mode_transcode
        },
    )
    return listOf(mode, play.reason).filter { it.isNotBlank() }.joinToString(" · ")
}
