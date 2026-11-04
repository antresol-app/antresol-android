package app.antresol.mobile.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.SeasonInfo
import app.antresol.mobile.ui.LoadViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A movie, series, season or episode with the profile's watched state. */
data class VideoDetail(
    val item: ItemDetail,
    /** Movie/episode: finished. Series/season: every episode watched. */
    val watched: Boolean,
) {
    val canWatchlist: Boolean get() = item.kind == Kind.MOVIE || item.kind == Kind.SERIES
}

/**
 * Loads `/api/items/{id}` (plus `/api/progress/{id}` for a movie or episode) and toggles the
 * watchlist and watched state optimistically; a failed write is undone and reported in [failed].
 */
class ItemDetailViewModel(private val client: AntresolClient, private val id: Long) :
    LoadViewModel<VideoDetail>(fetch = { fetchDetail(client, id) }) {

    /** Set when a toggle could not be saved; the screen shows it once and calls [consumeFailure]. */
    var failed: Boolean by mutableStateOf(false)
        private set

    fun consumeFailure() {
        failed = false
    }

    fun toggleWatchlist() = mutate(
        change = { it.copy(item = it.item.copy(inWatchlist = !it.item.inWatchlist)) },
        write = { before -> if (before.item.inWatchlist) client.removeFromWatchlist(id) else client.addToWatchlist(id) },
    )

    fun toggleWatched() = mutate(
        change = { detail ->
            val watched = !detail.watched
            detail.copy(watched = watched, item = detail.item.withEpisodes { it.copy(watched = watched, progress = 0.0) })
        },
        write = { before -> client.setWatched(id, !before.watched) },
    )

    fun toggleSeasonWatched(season: SeasonInfo) {
        val watched = !season.episodes.all { it.watched }
        mutate(
            change = { detail ->
                val item = detail.item.withEpisodes(season.id) { it.copy(watched = watched, progress = 0.0) }
                detail.copy(item = item, watched = item.allEpisodesWatched())
            },
            write = { client.setWatched(season.id, watched) },
        )
    }

    fun toggleEpisodeWatched(episode: EpisodeCard) = mutate(
        change = { detail ->
            val item = detail.item.withEpisodes(episodeId = episode.id) { it.copy(watched = !episode.watched, progress = 0.0) }
            detail.copy(item = item, watched = item.allEpisodesWatched())
        },
        write = { client.setWatched(episode.id, !episode.watched) },
    )

    private fun mutate(change: (VideoDetail) -> VideoDetail, write: suspend (before: VideoDetail) -> Unit) {
        val before = (state as? app.antresol.mobile.ui.LoadState.Ready)?.value ?: return
        update(change)
        viewModelScope.launch {
            try {
                write(before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { before }
                failed = true
            }
        }
    }

    private companion object {
        suspend fun fetchDetail(client: AntresolClient, id: Long): VideoDetail {
            val item = client.item(id)
            val watched = when (item.kind) {
                Kind.MOVIE, Kind.EPISODE -> client.progress(id).finished
                else -> item.allEpisodesWatched()
            }
            return VideoDetail(item, watched)
        }
    }
}

private fun ItemDetail.allEpisodesWatched(): Boolean {
    val episodes = seasons.flatMap { it.episodes }
    return episodes.isNotEmpty() && episodes.all { it.watched }
}

/** Applies [transform] to the episodes of one season, one episode, or (both null) every episode. */
private fun ItemDetail.withEpisodes(
    seasonId: Long? = null,
    episodeId: Long? = null,
    transform: (EpisodeCard) -> EpisodeCard,
): ItemDetail = copy(
    seasons = seasons.map { season ->
        if (seasonId != null && season.id != seasonId) return@map season
        season.copy(episodes = season.episodes.map { if (episodeId == null || it.id == episodeId) transform(it) else it })
    },
    next = next?.let { if (episodeId == null || it.id == episodeId) transform(it) else it },
)
