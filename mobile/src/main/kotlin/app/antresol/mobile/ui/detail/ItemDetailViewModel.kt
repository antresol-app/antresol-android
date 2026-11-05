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
import app.antresol.core.playback.Episodes
import app.antresol.core.playback.PendingWrites
import app.antresol.mobile.R
import app.antresol.core.playback.Resume
import app.antresol.mobile.ui.LoadViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the Play button starts: [itemId] (a movie or episode), [episode] for a series or season. */
data class PlayTarget(val itemId: Long, val episode: EpisodeCard?, val resume: Boolean)

/** A movie, series, season or episode with the profile's watched state. */
data class VideoDetail(
    val item: ItemDetail,
    /** Movie/episode: finished. Series/season: every episode watched. */
    val watched: Boolean,
    /** Movie/episode: the saved position (0 when none or finished). */
    val positionMs: Long = 0,
) {
    val canWatchlist: Boolean get() = item.kind == Kind.MOVIE || item.kind == Kind.SERIES

    /** Movies and episodes play themselves; a series or season plays its next (or first) episode. */
    val playTarget: PlayTarget?
        get() = when (item.kind) {
            Kind.MOVIE, Kind.EPISODE -> if (item.files.isEmpty()) null else PlayTarget(item.id, null, resume = positionMs > 0)
            Kind.SERIES, Kind.SEASON -> Episodes.toPlay(item)?.let { PlayTarget(it.id, it, resume = it.progress > 0.0 && !it.watched) }
            else -> null
        }
}

/**
 * Loads `/api/items/{id}` (plus `/api/progress/{id}` for a movie or episode) and toggles the
 * watchlist and watched state optimistically; a failed write is undone and reported in [failed].
 */
class ItemDetailViewModel(private val client: AntresolClient, private val id: Long, pendingWrites: PendingWrites) :
    LoadViewModel<VideoDetail>(pendingWrites = pendingWrites, fetch = { fetchDetail(client, id) }) {

    /** A string resource to show once (a toggle could not be saved); the screen calls [consumeNotice]. */
    var notice: Int? by mutableStateOf(null)
        private set

    fun consumeNotice() {
        notice = null
    }

    fun toggleWatchlist() = mutate(
        change = { it.copy(item = it.item.copy(inWatchlist = !it.item.inWatchlist)) },
        write = { before -> if (before.item.inWatchlist) client.removeFromWatchlist(id) else client.addToWatchlist(id) },
    )

    fun toggleWatched() = mutate(
        change = { detail ->
            val watched = !detail.watched
            // The server resets the position either way (0 when watched, deleted when not).
            detail.copy(watched = watched, positionMs = 0, item = detail.item.withEpisodes { it.copy(watched = watched, progress = 0.0) })
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
                notice = R.string.detail_action_failed
            }
        }
    }

    private companion object {
        suspend fun fetchDetail(client: AntresolClient, id: Long): VideoDetail {
            val item = client.item(id)
            return when (item.kind) {
                Kind.MOVIE, Kind.EPISODE -> {
                    val progress = client.progress(id)
                    VideoDetail(item, progress.finished, (Resume.videoStartSec(progress) * 1000).toLong())
                }
                else -> VideoDetail(item, item.allEpisodesWatched())
            }
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
