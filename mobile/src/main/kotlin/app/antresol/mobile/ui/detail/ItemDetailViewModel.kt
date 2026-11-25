package app.antresol.mobile.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.family.familyApi
import app.antresol.core.library.EpisodeGuide
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.EpisodeGuideResponse
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.TitleInfo
import app.antresol.core.model.TitleRef
import app.antresol.core.model.Trailer
import app.antresol.core.requests.requestsApi
import app.antresol.core.ways.WayTarget
import app.antresol.core.ways.WaysController
import app.antresol.core.ways.WaysSource
import app.antresol.core.ways.waysApi
import app.antresol.ui.LoadState
import app.antresol.ui.family.FamilyModel
import app.antresol.ui.requests.TitleRequest
import app.antresol.ui.trailers.Trailers
import app.antresol.core.playback.Episodes
import app.antresol.core.playback.PendingWrites
import app.antresol.mobile.R
import app.antresol.core.playback.Resume
import app.antresol.ui.LoadViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the Play button starts: [itemId] (a movie or episode), [episode] for a series or season. */
data class PlayTarget(val itemId: Long, val episode: EpisodeCard?, val resume: Boolean)

/** A movie, series, season or episode with the profile's watched state. */
data class VideoDetail(
    val item: ItemDetail,
    /** Movie/episode: finished. Series/season: every episode watched. */
    val watched: Boolean,
    /** Movie/episode: the saved position (0 when none or finished). */
    val positionMs: Long = 0,
    /** Series: the server's episode guide, null when it has none (the library's list then). */
    val guide: EpisodeGuideResponse? = null,
    /** The page of a title that is not in the library (`/api/title`): [item] is shaped from it, with id 0. */
    val title: TitleInfo? = null,
) {
    /** A movie or series off the library: it has no watchlist, watched state or trailers of its own. */
    val outsideLibrary: Boolean get() = item.id <= 0

    /** The library's own item for a title page opened for something the library has (the page then opens it instead). */
    val libraryItemId: Long? get() = title?.libraryItemId?.takeIf { title.inLibrary && it > 0 }

    /** The seasons the page lists: every episode of the guide, or the library's (watched toggles show at once). */
    fun episodeGuide(today: LocalDate = LocalDate.now()): EpisodeGuide =
        title?.let { EpisodeGuide.forTitle(it, guide, today) } ?: EpisodeGuide.build(item, guide, today)

    val canWatchlist: Boolean get() = !outsideLibrary && (item.kind == Kind.MOVIE || item.kind == Kind.SERIES)

    /** What the addons' ways act on: a movie or series with a TMDB id; null for anything else. */
    val wayTarget: WayTarget?
        get() {
            val tmdbId = item.tmdbId?.takeIf { it > 0 } ?: return null
            if (item.kind != Kind.MOVIE && item.kind != Kind.SERIES) return null
            return WayTarget(item.kind, tmdbId, inLibrary = !outsideLibrary, title = item.title, poster = item.poster, backdrop = item.backdrop)
        }

    /** Movies and episodes play themselves; a series or season plays its next (or first) episode. */
    val playTarget: PlayTarget?
        get() = when (item.kind) {
            Kind.MOVIE, Kind.EPISODE -> if (item.files.isEmpty()) null else PlayTarget(item.id, null, resume = positionMs > 0)
            Kind.SERIES, Kind.SEASON -> if (outsideLibrary) null else Episodes.toPlay(item)?.let { PlayTarget(it.id, it, resume = it.progress > 0.0 && !it.watched) }
            else -> null
        }
}

/**
 * Loads `/api/items/{id}` (plus `/api/progress/{id}` for a movie or episode) and toggles the
 * watchlist and watched state optimistically; a failed write is undone and reported in [failed].
 */
class ItemDetailViewModel(
    private val client: AntresolClient,
    private val id: Long,
    pendingWrites: PendingWrites,
    /** Set (with [id] 0): the page of a title that is not in the library, by its TMDB id. */
    title: TitleRef? = null,
    /** The addons' ways of the session; null: none are offered. */
    waysSource: WaysSource? = null,
    /** The tallest picture this device shows, for a way that picks a quality by it (null: no limit). */
    maxHeight: suspend () -> Int? = { null },
) : LoadViewModel<VideoDetail>(
    pendingWrites = pendingWrites,
    fetch = { if (title != null) fetchTitle(client, title) else fetchDetail(client, id) },
) {

    /** A string resource to show once (a toggle could not be saved); the screen calls [consumeNotice]. */
    var notice: Int? by mutableStateOf(null)
        private set

    fun consumeNotice() {
        notice = null
    }

    /** YouTube trailers (a season or episode gets its series'), best first; empty until loaded and when none. */
    var trailers: List<Trailer> by mutableStateOf(emptyList())
        private set

    /** The addons' ways for this page (buttons, the choice sheet, state rings); null without a source of them. */
    val ways: WaysController? = waysSource?.let { WaysController(client.waysApi(), it, viewModelScope, maxHeight) }

    /** A kid profile's «Попросить у родителя» on the page of a title off the library; null on any other page. */
    val request: TitleRequest? = if (title != null) TitleRequest(client.requestsApi(), viewModelScope) else null

    /** «Семья»: where the other profiles are in what this page shows (nobody for a kid, or for a title off the library). */
    val family = FamilyModel(client.familyApi(), viewModelScope)

    init {
        // A title off the library has no item to ask trailers of.
        if (title == null) viewModelScope.launch { trailers = Trailers.fetch(client, id) }
    }

    /** Shows string resource [res] once (an episode that is not in the library was tapped). */
    fun showNotice(res: Int) {
        notice = res
    }

    // Every change below sets a value worked out from what the page shows at the tap, never «the
    // opposite of what is there»: when a refused write is undone the accepted ones are made again
    // on the value before all of them, where an opposite would be the wrong one.

    fun toggleWatchlist() {
        val wanted = !(shown ?: return).item.inWatchlist
        mutate(
            change = { it.copy(item = it.item.copy(inWatchlist = wanted)) },
            write = { if (wanted) client.addToWatchlist(id) else client.removeFromWatchlist(id) },
        )
    }

    fun toggleWatched() {
        val watched = !(shown ?: return).watched
        mutate(
            // The server resets the position either way (0 when watched, deleted when not).
            change = { detail -> detail.copy(watched = watched, positionMs = 0, item = detail.item.withEpisodes { it.copy(watched = watched, progress = 0.0) }) },
            write = { client.setWatched(id, watched) },
        )
    }

    /** Marks the library episodes of season item [seasonId] ([episodes]) watched, or unwatched when all are. */
    fun toggleSeasonWatched(seasonId: Long, episodes: List<EpisodeCard>) {
        val watched = !episodes.all { it.watched }
        mutate(
            change = { detail ->
                val item = detail.item.withEpisodes(seasonId) { it.copy(watched = watched, progress = 0.0) }
                detail.copy(item = item, watched = item.allEpisodesWatched())
            },
            write = { client.setWatched(seasonId, watched) },
        )
    }

    fun toggleEpisodeWatched(episode: EpisodeCard) = mutate(
        change = { detail ->
            val item = detail.item.withEpisodes(episodeId = episode.id) { it.copy(watched = !episode.watched, progress = 0.0) }
            detail.copy(item = item, watched = item.allEpisodesWatched())
        },
        write = { client.setWatched(episode.id, !episode.watched) },
    )

    private val shown: VideoDetail? get() = (state as? LoadState.Ready)?.value

    private fun mutate(change: (VideoDetail) -> VideoDetail, write: suspend () -> Unit) =
        optimistic(change, onFailure = { notice = R.string.detail_action_failed }, write = write)

    private companion object {
        suspend fun fetchTitle(client: AntresolClient, ref: TitleRef): VideoDetail {
            val info = client.title(ref.kind, ref.tmdbId)
            val item = info.asItem()
            return VideoDetail(item, watched = false, guide = EpisodeGuide.fetch(client, item), title = info)
        }

        suspend fun fetchDetail(client: AntresolClient, id: Long): VideoDetail {
            val item = client.item(id)
            return when (item.kind) {
                Kind.MOVIE, Kind.EPISODE -> {
                    val progress = client.progress(id)
                    VideoDetail(item, progress.finished, (Resume.videoStartSec(progress) * 1000).toLong())
                }
                else -> VideoDetail(item, item.allEpisodesWatched(), guide = EpisodeGuide.fetch(client, item))
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
