package app.antresol.core.library

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.EpisodeGuideResponse
import app.antresol.core.model.GuideEntry
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.TitleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Where an episode of a series page stands. */
enum class EpisodeState {
    /** In the library: plays as before. */
    Downloaded,

    /** Aired, but not in the library: shown dimmed, does not play in the apps. */
    NotDownloaded,

    /** Not aired yet (air date after today, or not announced): greyed, not selectable. */
    Upcoming,
}

/**
 * One episode row of a series page. A [Downloaded] episode carries the library [card] to play and
 * open (its watched/progress are the card's, so the page's watched toggles keep working).
 */
data class GuideEpisode(
    val season: Int,
    val number: Int,
    val title: String,
    /** Null when unknown. */
    val airDate: LocalDate?,
    val state: EpisodeState,
    val card: EpisodeCard?,
    val watched: Boolean,
    /** 0…1, 0 when unknown. */
    val progress: Double,
    /**
     * News the profile has not watched: an aired episode the server flagged `new` (it has a source
     * other than the library's). The page marks it "New", as the web does.
     */
    val isNew: Boolean = false,
) {
    val isPlayable: Boolean get() = state == EpisodeState.Downloaded && card != null

    /** Unique within a page: the numbering and the library item. */
    val key: String get() = "episode-$season-$number-${card?.id ?: 0}"
}

data class GuideSeason(
    val number: Int,
    /** The library season's title, or "" (the app then shows "Season N" / "Specials"). */
    val title: String,
    /** The library season item (for "mark season watched"), null when the library has none of it. */
    val libraryId: Long?,
    val episodes: List<GuideEpisode>,
) {
    /** The library episodes of the season, as today's list had them. */
    val libraryEpisodes: List<EpisodeCard> get() = episodes.mapNotNull { it.card }
}

/**
 * The seasons a series page lists. [fromServer] is false when the episode guide was not available
 * (old server, age filter, any failure): the page then shows the library's episodes, as before.
 */
data class EpisodeGuide(val seasons: List<GuideSeason>, val fromServer: Boolean) {
    companion object {
        /**
         * Asks the server for the guide of a library series with a TMDB id; null (fall back
         * silently) for anything else and for every failure: 404 (hidden by the age filter),
         * 404/405 (a server before 0.5.0), network or decoding errors, and an answer slower than
         * [timeoutMs] (the server may be asking TMDB; the page must not wait on it).
         */
        suspend fun fetch(client: AntresolClient, item: ItemDetail, timeoutMs: Long = FETCH_TIMEOUT_MS): EpisodeGuideResponse? {
            if (item.kind != Kind.SERIES) return null
            val tmdbId = item.tmdbId?.takeIf { it > 0 } ?: return null
            return withTimeoutOrNull(timeoutMs) {
                try {
                    client.episodeGuide(Kind.SERIES, tmdbId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }

        const val FETCH_TIMEOUT_MS = 6_000L

        /**
         * The seasons of a series [title] that is not in the library: the server's [guide] (each
         * episode not downloaded, or upcoming), else the title's season counts; those rows have no
         * name or date, and none is called upcoming.
         */
        fun forTitle(title: TitleInfo, guide: EpisodeGuideResponse?, today: LocalDate): EpisodeGuide {
            if (guide != null && guide.episodes.isNotEmpty()) return build(title.asItem(), guide, today)
            val seasons = title.seasons.filter { it.number > 0 && it.episodes > 0 }.sortedBy { it.number }.map { season ->
                GuideSeason(
                    number = season.number,
                    title = "",
                    libraryId = null,
                    episodes = (1..season.episodes).map { number ->
                        GuideEpisode(season.number, number, "", null, EpisodeState.NotDownloaded, card = null, watched = false, progress = 0.0)
                    },
                )
            }
            return EpisodeGuide(seasons, fromServer = false)
        }

        /** The library's seasons alone (today's list). */
        fun library(item: ItemDetail): EpisodeGuide = EpisodeGuide(
            seasons = item.seasons.filter { it.episodes.isNotEmpty() }.sortedBy { it.number }.map { season ->
                GuideSeason(
                    number = season.number,
                    title = season.title,
                    libraryId = season.id,
                    episodes = season.episodes.map { downloaded(it, season.number) },
                )
            },
            fromServer = false,
        )

        /**
         * Merges the library series [item] with the server's [guide] (null or empty = [library]).
         * Every guide episode shows; one with a library source (or a library episode at the same
         * season and number) is [EpisodeState.Downloaded] with its library card; the rest are
         * [EpisodeState.Upcoming] when their air date is after [today] or unknown, else
         * [EpisodeState.NotDownloaded]. Library episodes the guide misses are kept.
         */
        fun build(item: ItemDetail, guide: EpisodeGuideResponse?, today: LocalDate): EpisodeGuide {
            if (guide == null || guide.episodes.isEmpty()) return library(item)
            val librarySeasons = item.seasons.filter { it.episodes.isNotEmpty() }
            val seasonOf = HashMap<Long, Int>()
            val byId = HashMap<Long, EpisodeCard>()
            val byNumber = HashMap<Pair<Int, Int>, EpisodeCard>()
            for (season in librarySeasons) for (card in season.episodes) {
                seasonOf[card.id] = season.number
                byId[card.id] = card
                byNumber.putIfAbsent(season.number to card.episodeNo, card)
            }
            // Library sources first, then the season and number for the rest, so a library episode
            // numbered differently from TMDB is not taken by the wrong row.
            val cards = arrayOfNulls<EpisodeCard>(guide.episodes.size)
            val used = HashSet<Long>()
            guide.episodes.forEachIndexed { i, entry ->
                val itemId = entry.librarySource?.meta?.itemId?.takeIf { it > 0 } ?: return@forEachIndexed
                cards[i] = byId[itemId] ?: synthesized(itemId, entry)
                used += itemId
            }
            guide.episodes.forEachIndexed { i, entry ->
                if (cards[i] != null) return@forEachIndexed
                cards[i] = byNumber[entry.season to entry.number]?.takeIf { it.id !in used }?.also { used += it.id }
            }
            val rows = ArrayList<GuideEpisode>()
            guide.episodes.forEachIndexed { i, entry ->
                val card = cards[i]
                val airDate = parseDate(entry.airDate)
                rows += if (card != null) {
                    // A file holding two episodes is the same card on both rows.
                    downloaded(card, entry.season).let {
                        it.copy(number = entry.number, title = card.title.ifBlank { entry.name }, airDate = airDate, isNew = entry.new && !it.watched)
                    }
                } else {
                    GuideEpisode(
                        season = entry.season,
                        number = entry.number,
                        title = entry.name,
                        airDate = airDate,
                        state = if (airDate == null || airDate.isAfter(today)) EpisodeState.Upcoming else EpisodeState.NotDownloaded,
                        card = null,
                        watched = entry.watched,
                        progress = 0.0,
                        isNew = entry.new && !entry.watched && airDate != null && !airDate.isAfter(today),
                    )
                }
            }
            for ((id, card) in byId) if (id !in used) rows += downloaded(card, seasonOf.getValue(id))

            val titles = librarySeasons.associate { it.number to it }
            val seasons = rows.groupBy { it.season }.toSortedMap().map { (number, episodes) ->
                val library = titles[number]
                GuideSeason(
                    number = number,
                    title = library?.title.orEmpty(),
                    libraryId = library?.id,
                    episodes = episodes.sortedBy { it.number },
                )
            }
            return EpisodeGuide(seasons, fromServer = true)
        }

        private fun downloaded(card: EpisodeCard, season: Int) = GuideEpisode(
            season = season,
            number = card.episodeNo,
            title = card.title,
            airDate = null,
            state = EpisodeState.Downloaded,
            card = card,
            watched = card.watched,
            progress = card.progress,
        )

        /** A library episode the series detail does not list (its season was filtered out, say). */
        private fun synthesized(itemId: Long, entry: GuideEntry) = EpisodeCard(
            id = itemId,
            title = entry.name,
            seasonNo = entry.season,
            episodeNo = entry.number,
            watched = entry.watched,
            thumb = "/api/images/frame/$itemId",
        )

        internal fun parseDate(text: String): LocalDate? = try {
            if (text.length < 10) null else LocalDate.parse(text.substring(0, 10))
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
