package app.antresol.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.antresol.core.model.PlayMode
import app.antresol.core.model.Quality
import app.antresol.ui.R
import app.antresol.ui.format.TrackLabels

/** One choice of a menu: its label, whether it is the current one, and a small badge after the label. */
data class MenuOption(val label: String, val selected: Boolean, val badge: String? = null, val onSelect: () -> Unit)

/** The menus of the video player. */
enum class PlayerMenu { Audio, Subtitles, Quality }

/** What a [PlayerMenu] shows: its title, the choices and, for the quality, how the stream is delivered. */
class PlayerMenuContent(val title: String, val options: List<MenuOption>, val note: String?)

/** The audio, subtitle or quality choices of [viewModel]'s video; each app draws them its own way. */
@Composable
fun playerMenu(menu: PlayerMenu, viewModel: VideoPlayerViewModel): PlayerMenuContent {
    val resources = LocalContext.current.resources
    val title = stringResource(
        when (menu) {
            PlayerMenu.Audio -> R.string.detail_audio
            PlayerMenu.Subtitles -> R.string.detail_subtitles
            PlayerMenu.Quality -> R.string.detail_quality
        },
    )
    val options = when (menu) {
        PlayerMenu.Audio -> viewModel.audioMenu.let { m ->
            // a cleaned voice-over is not a row: «Очищенный перевод» under the voices
            m.rows.map { row ->
                MenuOption(
                    TrackLabels.audio(resources, row.track),
                    row.checked,
                    badge = if (row.track.isCleaned) resources.getString(R.string.track_cleaned) else null,
                ) { viewModel.selectAudio(row.plays) }
            } + listOfNotNull(
                m.toggle?.let { t ->
                    MenuOption(resources.getString(R.string.track_cleaned_toggle), t.on, badge = null) { viewModel.selectAudio(t.target) }
                },
            )
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
    return PlayerMenuContent(title, options, note = if (menu == PlayerMenu.Quality) streamNote(viewModel) else null)
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
private fun streamNote(viewModel: VideoPlayerViewModel): String? {
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
