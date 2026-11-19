package app.antresol.tv.homechannels

import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.Progress

/** A card of the «continue» shelf with the position the server holds for it (null: none was asked or found). */
data class ContinueItem(val card: Card, val progress: Progress?)

/**
 * What the server says for the signed-in profile, as the home screen needs it. The server has already
 * applied everything that decides what this profile may see (kid rating limits, the device's own history).
 */
data class HomeSnapshot(
    val serverId: String,
    val userId: Long,
    val continueItems: List<ContinueItem>,
    val recentMovies: List<Card>,
    val recentSeries: List<Card>,
)

/** Makes the programs of the two slots out of a [HomeSnapshot]. */
object HomeMapper {
    /** Tiles of the Watch Next row; the row is shared with other apps and shows only the first few. */
    const val MAX_WATCH_NEXT = 10

    /** Tiles of the «Antresol» channel. */
    const val MAX_CHANNEL = 20

    private const val SUBTITLE_SEPARATOR = " · "

    /**
     * «Continue» and next-up episodes, in the server's order. A video with saved progress is a
     * [WatchNextKind.Continue] with its position; one without (the next episode of a series) is a [WatchNextKind.Next].
     * Finished and non-video cards are left out.
     */
    fun watchNext(snapshot: HomeSnapshot): List<ProgramSpec> =
        snapshot.continueItems
            .filter { it.card.kind in Kind.VIDEO && !it.card.watched && it.progress?.finished != true }
            .distinctBy { it.card.id }
            .take(MAX_WATCH_NEXT)
            .mapIndexed { rank, item -> watchNextProgram(snapshot, item, rank) }

    private fun watchNextProgram(snapshot: HomeSnapshot, item: ContinueItem, rank: Int): ProgramSpec {
        val card = item.card
        val saved = item.progress?.takeIf { card.progress > 0.0 && it.positionMs > 0 && it.durationMs > 0 }
        val episodeTexts = if (card.kind == Kind.EPISODE) splitEpisode(card) else null
        // The wide picture is the episode's own frame (else the title's backdrop); a title with only a poster shows that.
        val wide = card.thumb.isNotEmpty() || card.backdrop.isNotEmpty()
        return ProgramSpec(
            key = key("wn", snapshot, card),
            slot = Slot.WatchNext,
            kind = programKind(card.kind),
            watchNext = if (saved != null) WatchNextKind.Continue else WatchNextKind.Next,
            title = episodeTexts?.series ?: card.title,
            description = episodeTexts?.rest ?: card.subtitle.ifBlank { card.year?.toString().orEmpty() },
            episodeTitle = if (episodeTexts != null) card.title.takeIf { it.isNotBlank() } else null,
            season = card.season,
            episode = card.episode,
            year = card.year,
            positionMs = saved?.positionMs?.coerceAtMost(saved.durationMs) ?: 0,
            durationMs = saved?.durationMs ?: 0,
            artwork = if (wide) ArtworkRef(card.wideImage, card.wideFallback) else ArtworkRef(card.poster),
            aspect = if (wide) Aspect.Wide else Aspect.Poster,
            link = HomeLink.Play(card.id, snapshot.userId),
            rank = rank,
        )
    }

    /**
     * «New in the library»: recent movies and new series, one after the other (the shelves are newest first), without
     * what the profile has watched, what it is already continuing (it is in Watch Next) and titles with no poster to show.
     */
    fun channel(snapshot: HomeSnapshot): List<ProgramSpec> {
        val continuing = snapshot.continueItems.map { it.card.id }.toSet()
        val continuingSeries = snapshot.continueItems.mapNotNull { it.card.seriesId }.toSet()
        fun Card.fresh() = !watched && poster.isNotEmpty() && id !in continuing && id !in continuingSeries
        val movies = snapshot.recentMovies.filter { it.kind == Kind.MOVIE && it.fresh() }
        val series = snapshot.recentSeries.filter { it.kind == Kind.SERIES && it.fresh() }
        return interleave(movies, series)
            .distinctBy { it.kind to it.id }
            .take(MAX_CHANNEL)
            .mapIndexed { rank, card ->
                ProgramSpec(
                    key = key("ch", snapshot, card),
                    slot = Slot.Channel,
                    kind = programKind(card.kind),
                    title = card.title,
                    description = card.subtitle.ifBlank { card.year?.toString().orEmpty() },
                    year = card.year,
                    artwork = ArtworkRef(card.poster),
                    aspect = Aspect.Poster,
                    link = HomeLink.Open(card.kind, card.id, snapshot.userId),
                    rank = rank,
                )
            }
    }

    /** The series and the rest of an episode card's subtitle («The Show · Season 1, Episode 2»), when it has the shape. */
    internal data class EpisodeTexts(val series: String, val rest: String)

    internal fun splitEpisode(card: Card): EpisodeTexts? {
        val at = card.subtitle.indexOf(SUBTITLE_SEPARATOR)
        if (at <= 0) return null
        return EpisodeTexts(card.subtitle.substring(0, at).trim(), card.subtitle.substring(at + SUBTITLE_SEPARATOR.length).trim())
            .takeIf { it.series.isNotEmpty() }
    }

    private fun programKind(kind: String): ProgramKind = when (kind) {
        Kind.MOVIE -> ProgramKind.Movie
        Kind.EPISODE -> ProgramKind.Episode
        else -> ProgramKind.Series
    }

    private fun key(prefix: String, snapshot: HomeSnapshot, card: Card) =
        "$prefix:${snapshot.serverId}:${snapshot.userId}:${card.kind}:${card.id}"

    private fun <T> interleave(first: List<T>, second: List<T>): List<T> = buildList {
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let(::add)
            second.getOrNull(i)?.let(::add)
        }
    }
}
