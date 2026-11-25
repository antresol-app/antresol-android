package app.antresol.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.antresol.core.family.FamilyKind
import app.antresol.core.model.PlayMode
import app.antresol.core.model.Quality
import app.antresol.ui.R
import app.antresol.ui.family.familyMenuOptions
import app.antresol.ui.format.TrackLabels

/**
 * One choice of a menu: its label, whether it is the current one, and a small badge after the label.
 * A [section] is a heading the menu draws above this choice (the first of a group), a [divider] a thin line.
 * A [detail] is a second line under the label, [more] says the choice leads on to another list, and
 * [onLongSelect] is what a long press does (hold OK) when that is something else. A [leading] picture (given the row's
 * content colour) takes the place of the tick, and an [action] is a line of its own under the [detail] (what choosing does).
 */
data class MenuOption(
    val label: String,
    val selected: Boolean,
    val badge: String? = null,
    val section: String? = null,
    val detail: String? = null,
    val more: Boolean = false,
    val divider: Boolean = false,
    val leading: (@Composable (tint: Color) -> Unit)? = null,
    val action: String? = null,
    val onLongSelect: (() -> Unit)? = null,
    val onSelect: () -> Unit,
)

/** The menus of the video player. */
enum class PlayerMenu { Audio, Subtitles, Quality, AutoSkip, Family }

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
            PlayerMenu.AutoSkip -> R.string.player_auto_skip
            PlayerMenu.Family -> R.string.family_title
        },
    )
    val options = when (menu) {
        PlayerMenu.Audio -> viewModel.audioMenu.let { m ->
            // a cleaned voice-over is not a row: «Очищенный перевод» under the voices
            val tracks = m.rows.map { row ->
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
            // A stream an addon serves lists no tracks in its answer: the ones inside the stream itself.
            val own = if (viewModel.audioTracks.isEmpty() && viewModel.streamAudio.size > 1) {
                viewModel.streamAudio.map { track ->
                    MenuOption(TrackLabels.audio(resources, track), track.index == viewModel.streamAudioSelected) { viewModel.selectStreamAudio(track) }
                }
            } else {
                emptyList()
            }
            val shown = tracks + own
            // «Audio & source…»: the way's own choice again, from here (a way's stream only).
            val source = if (viewModel.canChooseSource) {
                listOf(MenuOption(stringResource(R.string.player_way_source), selected = false, more = true, divider = shown.isNotEmpty()) { viewModel.openSourceChoice() })
            } else {
                emptyList()
            }
            shown + source
        }
        PlayerMenu.Subtitles -> listOf(
            MenuOption(
                stringResource(R.string.detail_subtitles_off),
                viewModel.selectedSubtitle == null && viewModel.streamTextSelected < 0,
            ) { viewModel.selectSubtitle(null) },
        ) + viewModel.subtitleTracks.map { track ->
            MenuOption(TrackLabels.subtitle(resources, track), track.index == viewModel.selectedSubtitle?.index) { viewModel.selectSubtitle(track) }
        } + if (viewModel.subtitleTracks.isEmpty()) {
            // The subtitles inside a stream an addon serves, when its answer lists none.
            viewModel.streamText.map { track ->
                MenuOption(TrackLabels.subtitle(resources, track), track.index == viewModel.streamTextSelected) { viewModel.selectStreamText(track) }
            }
        } else {
            emptyList()
        }
        // The core's qualities, or for a stream an addon serves the names its answer lists.
        PlayerMenu.Quality -> viewModel.wayQualities.ifEmpty { null }?.map { name ->
            MenuOption(name, name == viewModel.quality) { viewModel.selectQuality(name) }
        } ?: Quality.ALL.map { value ->
            MenuOption(qualityLabel(value), value == viewModel.quality) { viewModel.selectQuality(value) }
        }
        // «Семья»: the profiles that are in this item; choosing one continues from their place
        PlayerMenu.Family -> familyMenuOptions(
            resources,
            FamilyKind.of(viewModel.item?.kind.orEmpty()) ?: FamilyKind.Movie,
            viewModel.familyHere,
            emptyList(),
            viewModel::familyPhoto,
            viewModel::jumpToFamily,
        )
        // «Пропускать сам»: each row turns its kind on or off (the profile's, on every device)
        PlayerMenu.AutoSkip -> viewModel.autoSkip.let { a ->
            listOf(
                MenuOption(stringResource(R.string.player_auto_recap), a.recap) { viewModel.changeAutoSkip(a.copy(recap = !a.recap)) },
                MenuOption(stringResource(R.string.player_auto_intro), a.intro) { viewModel.changeAutoSkip(a.copy(intro = !a.intro)) },
                MenuOption(stringResource(R.string.player_auto_credits), a.credits) { viewModel.changeAutoSkip(a.copy(credits = !a.credits)) },
            )
        }
    }
    val note = when (menu) {
        PlayerMenu.Quality -> streamNote(viewModel)
        PlayerMenu.Family -> stringResource(R.string.family_note)
        else -> null
    }
    return PlayerMenuContent(title, options, note)
}

/**
 * The live channel player's «Качество», in the film player's [PlayerMenuContent] (same rows, same
 * labels); null when the channel offers no choice (an external stream with a single variant).
 */
@Composable
fun liveQualityMenu(viewModel: LivePlayerViewModel): PlayerMenuContent? {
    val options = viewModel.qualityOptions.ifEmpty { return null }
    val auto = stringResource(R.string.quality_auto)
    val original = stringResource(R.string.quality_original)
    fun label(value: String) = when (value) {
        Quality.AUTO -> auto
        Quality.ORIGINAL -> original
        else -> "${value}p"
    }
    return PlayerMenuContent(
        title = stringResource(R.string.detail_quality),
        options = options.map { option ->
            // «Авто · 720p»: what the server applies away from home (as the web player shows it)
            val text = option.applied?.let { "${label(option.value)} · ${label(it)}" } ?: label(option.value)
            MenuOption(text, option.selected) { viewModel.selectQuality(option.value) }
        },
        note = null,
    )
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
    // An addon's stream is not delivered by the server's own modes: its qualities are the addon's.
    if (viewModel.wayQualities.isNotEmpty()) return null
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
