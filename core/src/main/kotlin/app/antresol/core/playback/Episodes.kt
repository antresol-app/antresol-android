package app.antresol.core.playback

import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.ItemDetail

/** Episode order of a series detail (`GET /api/items/{seriesId}`), for "next episode" autoplay. */
object Episodes {
    /** All episodes in season then episode order. */
    fun ordered(series: ItemDetail): List<EpisodeCard> =
        series.seasons.sortedBy { it.number }.flatMap { season -> season.episodes.sortedBy { it.episodeNo } }

    /** The episode after [episodeId], or null when it is the last one (or not part of [series]). */
    fun after(series: ItemDetail, episodeId: Long): EpisodeCard? {
        val all = ordered(series)
        val index = all.indexOfFirst { it.id == episodeId }
        return if (index < 0) null else all.getOrNull(index + 1)
    }

    /** What "Play" on a series starts: the server's `next`, else the first episode. */
    fun toPlay(series: ItemDetail): EpisodeCard? = series.next ?: ordered(series).firstOrNull()
}
