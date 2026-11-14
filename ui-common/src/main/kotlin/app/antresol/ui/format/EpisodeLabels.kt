package app.antresol.ui.format

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.antresol.core.library.EpisodeState
import app.antresol.core.library.GuideEpisode
import app.antresol.core.library.GuideSeason
import app.antresol.ui.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Labels of the episode guide on series pages. */
object EpisodeLabels {
    /** «21 ноября», "Nov 21"; the year only when it is not [today]'s. */
    fun airDate(date: LocalDate, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
        val skeleton = if (date.year == today.year) "dMMMM" else "dMMMMy"
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    }

    /**
     * The mark of an episode: Downloaded (only when the guide answered: [guided]), Not downloaded,
     * «Выйдет 21 ноября» / Not aired yet. Null when there is nothing to say.
     */
    fun state(resources: Resources, episode: GuideEpisode, guided: Boolean, today: LocalDate = LocalDate.now()): String? = when (episode.state) {
        EpisodeState.Downloaded -> if (guided) resources.getString(R.string.episode_downloaded) else null
        EpisodeState.NotDownloaded -> resources.getString(R.string.episode_not_downloaded)
        EpisodeState.Upcoming -> episode.airDate?.let { resources.getString(R.string.episode_airs, airDate(it, today)) }
            ?: resources.getString(R.string.episode_not_aired)
    }

    /** How strongly an episode row draws: a missing one is dimmer, an upcoming one greyed. */
    fun alpha(state: EpisodeState): Float = when (state) {
        EpisodeState.Downloaded -> 1f
        EpisodeState.NotDownloaded -> 0.6f
        EpisodeState.Upcoming -> 0.38f
    }

    /** The season's pill: its library title, "Specials" for season 0, else "Season N". */
    fun season(resources: Resources, season: GuideSeason): String = season.title.ifBlank {
        if (season.number == 0) resources.getString(R.string.detail_specials) else resources.getString(R.string.detail_season_number, season.number)
    }
}

@Composable
fun episodeStateLabel(episode: GuideEpisode, guided: Boolean): String? = EpisodeLabels.state(LocalContext.current.resources, episode, guided)

@Composable
fun seasonLabel(season: GuideSeason): String = EpisodeLabels.season(LocalContext.current.resources, season)
