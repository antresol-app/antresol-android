package app.antresol.tv.ui.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.library.EpisodeGuide
import app.antresol.core.library.GuideSeason
import app.antresol.core.playback.PendingWrites
import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.AudioTrack
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.CastMember
import app.antresol.core.model.EpisodeGuideResponse
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.KidRequestState
import app.antresol.core.model.Playlist
import app.antresol.core.model.PlaylistDetail
import app.antresol.core.model.Progress
import app.antresol.core.model.ShuffleSource
import app.antresol.core.model.SubTrack
import app.antresol.core.model.Track
import app.antresol.core.model.TitleInfo
import app.antresol.core.model.Trailer
import app.antresol.core.requests.requestsApi
import app.antresol.core.ways.MemoryWayDefaults
import app.antresol.core.ways.WayDefaults
import app.antresol.core.ways.WayTarget
import app.antresol.core.ways.WaysController
import app.antresol.core.ways.WaysSource
import app.antresol.core.ways.waysApi
import app.antresol.ui.requests.TitleRequest
import app.antresol.ui.trailers.Trailers
import app.antresol.core.playback.Episodes
import app.antresol.core.playback.Resume
import app.antresol.core.music.PlaylistEdits
import app.antresol.core.reading.ComicProgress
import app.antresol.tv.R as TvR
import app.antresol.tv.playback.AudioContent
import app.antresol.tv.playback.AudioEngine
import app.antresol.ui.LoadState
import app.antresol.ui.load
import app.antresol.ui.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A row of related cards on a detail page; the title is server text or a string resource. */
data class DetailRow(
    val id: String,
    val title: String = "",
    @StringRes val titleRes: Int? = null,
    val cards: List<Card>,
)

/** What the detail page shows, unified over items, albums, artists, playlists and books. */
data class DetailModel(
    val card: Card,
    val byline: String = "",
    val narrator: String = "",
    val durationMs: Long? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val songCount: Int? = null,
    val overview: String = "",
    val rows: List<DetailRow> = emptyList(),
    /** Play, watchlist and watched state of movies, series, seasons and episodes. */
    val video: VideoInfo? = null,
    /** Tracks, favorite and shuffle of albums, playlists and artists. */
    val music: MusicInfo? = null,
    /** Listen / read actions of books, audiobooks and comics. */
    val book: BookInfo? = null,
)

/** The playable side of an album, playlist or artist page. */
data class MusicInfo(
    val tracks: List<Track>,
    @StringRes val tracksTitle: Int,
    /** Albums and artists can be favorited; playlists cannot. */
    val canStar: Boolean,
    val starred: Boolean,
    /** Artists shuffle all their songs on the server; albums and playlists shuffle their own tracks. */
    val shuffle: ShuffleSource? = null,
)

/** The comic volume a Read/Continue button opens, and the 1-based page it continues at. */
data class ComicTarget(val volumeId: Long, val continuePage: Int?)

/** The actions of a book, audiobook, comic series or comic volume page. */
data class BookInfo(
    val detail: BookDetail,
    val want: Boolean,
    /** Whole-book position to resume an audiobook at (0 = start). */
    val resumeMs: Long = 0,
    val comic: ComicTarget? = null,
) {
    val isAudiobook: Boolean get() = detail.kind == Kind.AUDIOBOOK && detail.files.isNotEmpty()
    val isEbook: Boolean get() = detail.kind == Kind.BOOK
}

/** What "Play" starts: the item itself, or for a series the episode to continue with. */
data class PlayTarget(
    val itemId: Long,
    val resumeMs: Long,
    val season: Int? = null,
    val episode: Int? = null,
)

/** The playable side of a movie, episode, series or season detail page. */
data class VideoInfo(
    val playTarget: PlayTarget?,
    val canWatchlist: Boolean,
    val inWatchlist: Boolean,
    val watched: Boolean,
    val audio: List<AudioTrack> = emptyList(),
    val subtitles: List<SubTrack> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    /** The seasons a series page lists: the server's episode guide, or the library's episodes. */
    val guide: EpisodeGuide = EpisodeGuide(emptyList(), fromServer = false),
    /** The TMDB id of a movie or series (what the addons' ways name it by). */
    val tmdbId: Long? = null,
    /** The page of a movie or series that is not in the library (`/api/title`): no watchlist, watched state or trailers. */
    val outsideLibrary: Boolean = false,
    /** A title page opened for something the library has: the library's item, to open instead. */
    val libraryItemId: Long? = null,
    /** A kid profile's «Попросить» of the title (a title page only; null for anyone else). */
    val kidRequest: KidRequestState? = null,
) {
    val seasons: List<GuideSeason> get() = guide.seasons
}

/** Loads the detail of any card kind through the matching endpoint. */
class DetailViewModel(
    private val client: AntresolClient,
    private val audio: AudioEngine,
    private val kind: String,
    private val id: Long,
    private val pendingWrites: PendingWrites,
    /** Set (with [id] 0): the page of a movie or series that is not in the library, by its TMDB id. */
    private val tmdbId: Long = 0,
    /** The addons' ways of the session; null: none are offered. */
    waysSource: WaysSource? = null,
    /** The tallest picture this TV shows, for a way that picks a quality by it (null: no limit). */
    maxHeight: suspend () -> Int? = { null },
    /** What each title plays with «Play» (kept on this device); forgotten with the page by default. */
    wayDefaults: WayDefaults = MemoryWayDefaults(),
) : ViewModel() {
    var state: LoadState<DetailModel> by mutableStateOf(LoadState.Loading)
        private set

    /** A failed watchlist/watched action, shown under the buttons until the next action. */
    var actionError: Throwable? by mutableStateOf(null)
        private set

    /**
     * YouTube trailers of a movie or series page (a season or episode shows its series'), best
     * first; empty until they arrive, and when there are none. Loaded beside the page, never
     * holding it up.
     */
    var trailers: List<Trailer> by mutableStateOf(emptyList())
        private set

    /** The addons' ways for this page (buttons, the choice panel, state rings); null without a source of them. */
    val ways: WaysController? = waysSource?.let { WaysController(client.waysApi(), it, viewModelScope, maxHeight, defaults = wayDefaults) }

    /** A kid profile's «Попросить у родителя» on the page of a title off the library; null on any other page. */
    val request: TitleRequest? = if (tmdbId > 0) TitleRequest(client.requestsApi(), viewModelScope) else null

    private var job: Job? = null

    /**
     * The watchlist/watched/star/want request in flight. Presses meanwhile are ignored, so add and
     * remove never race and the page always shows what the server ends up with.
     */
    private var toggleJob: Job? = null

    init {
        reload()
        // A write that finishes with the page already back in front (a reader saves its last page as
        // it closes, after this page resumed) is shown too.
        viewModelScope.launch {
            pendingWrites.completions.drop(1).collect {
                job?.join()
                refresh()
            }
        }
        // A title off the library has no item to ask trailers of.
        if (kind in Kind.VIDEO && tmdbId == 0L) viewModelScope.launch { trailers = Trailers.fetch(client, id, kind) }
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch { show(load { fetch() }) }
    }

    /** Shows what was loaded; the title's request state is the server's as of now. */
    private fun show(loaded: LoadState<DetailModel>) {
        state = loaded
        (loaded as? LoadState.Ready)?.value?.video?.let { request?.load(it.kidRequest) }
    }

    /** Re-reads the page in place (back from the player); failures keep what is shown. */
    fun refresh() {
        if (state !is LoadState.Ready || job?.isActive == true || toggleJob?.isActive == true) return
        job = viewModelScope.launch {
            pendingWrites.await()
            val fresh = load { fetch() }
            if (fresh is LoadState.Ready) show(fresh)
        }
    }

    fun toggleWatchlist() {
        if (toggleJob?.isActive == true) return
        val model = (state as? LoadState.Ready)?.value ?: return
        val video = model.video ?: return
        val add = !video.inWatchlist
        state = LoadState.Ready(model.copy(video = video.copy(inWatchlist = add)))
        toggleJob = act(onFailure = { state = LoadState.Ready(model) }) {
            if (add) client.addToWatchlist(model.card.id) else client.removeFromWatchlist(model.card.id)
        }
    }

    fun toggleWatched() {
        if (toggleJob?.isActive == true) return
        val model = (state as? LoadState.Ready)?.value ?: return
        val video = model.video ?: return
        val watched = !video.watched
        state = LoadState.Ready(model.copy(video = video.copy(watched = watched)))
        toggleJob = act(onFailure = { state = LoadState.Ready(model) }) {
            client.setWatched(model.card.id, watched)
            // Episodes, progress bars and the next episode all change; read them again.
            state = LoadState.Ready(fetch())
        }
    }

    /** Plays the page's tracks from [index], or shuffled. */
    fun playTracks(index: Int, shuffled: Boolean = false, onStarted: () -> Unit) {
        val music = (state as? LoadState.Ready)?.value?.music ?: return
        val source = music.shuffle
        if (shuffled && source != null) {
            act(onFailure = {}) {
                val queue = client.shuffle(source).tracks
                if (queue.isNotEmpty()) {
                    audio.playTracks(queue)
                    onStarted()
                }
            }
            return
        }
        if (music.tracks.isEmpty()) return
        audio.playTracks(music.tracks, index, shuffled)
        onStarted()
    }

    fun toggleStar() {
        if (toggleJob?.isActive == true) return
        val model = (state as? LoadState.Ready)?.value ?: return
        val music = model.music?.takeIf { it.canStar } ?: return
        val star = !music.starred
        state = LoadState.Ready(model.copy(music = music.copy(starred = star)))
        toggleJob = act(onFailure = { state = LoadState.Ready(model) }) {
            if (star) client.star(model.card.id) else client.unstar(model.card.id)
        }
    }

    /** Listens to the audiobook from the saved position, the start, or [fromMs]; opens Now Playing. */
    fun listen(fromStart: Boolean = false, fromMs: Long? = null, onStarted: () -> Unit) {
        val book = (state as? LoadState.Ready)?.value?.book?.takeIf { it.isAudiobook } ?: return
        val playing = audio.content as? AudioContent.Audiobook
        if (!fromStart && fromMs == null && playing?.book?.card?.id == book.detail.card.id) {
            onStarted()
            return
        }
        audio.playAudiobook(book.detail, fromMs ?: if (fromStart) 0L else book.resumeMs)
        onStarted()
    }

    fun toggleWant() {
        if (toggleJob?.isActive == true) return
        val model = (state as? LoadState.Ready)?.value ?: return
        val book = model.book ?: return
        val want = !book.want
        state = LoadState.Ready(model.copy(book = book.copy(want = want)))
        toggleJob = act(onFailure = { state = LoadState.Ready(model) }) {
            if (want) client.addToWatchlist(model.card.id) else client.removeFromWatchlist(model.card.id)
        }
    }

    /** The profile's playlists for "Add to Playlist"; null until asked for. */
    var playlists: LoadState<List<Playlist>>? by mutableStateOf(null)
        private set

    /** The playlist the page's songs were last added to, shown under the buttons. */
    var addedTo: String? by mutableStateOf(null)
        private set

    fun loadPlaylists() {
        playlists = LoadState.Loading
        viewModelScope.launch { playlists = load { client.playlists().items } }
    }

    /** Albums: appends every song of the page to [playlist]. */
    fun addToPlaylist(playlist: Playlist) = writePlaylist(playlist.name) { tracks ->
        client.updatePlaylist(playlist.id, PlaylistEdits.append(tracks))
    }

    /** Albums: creates a playlist named [name] holding the page's songs. */
    fun createPlaylist(name: String) = writePlaylist(name.trim()) { tracks ->
        client.createPlaylist(name.trim(), tracks)
    }

    /** Playlists: deletes this one, then [onDeleted] (the page closes). */
    fun deletePlaylist(onDeleted: () -> Unit) {
        if (toggleJob?.isActive == true) return
        toggleJob = act(onFailure = {}) {
            client.deletePlaylist(id)
            onDeleted()
        }
    }

    private fun writePlaylist(name: String, write: suspend (List<Long>) -> Unit) {
        if (toggleJob?.isActive == true || name.isEmpty()) return
        val tracks = (state as? LoadState.Ready)?.value?.music?.tracks?.map { it.id }.orEmpty()
        if (tracks.isEmpty()) return
        addedTo = null
        toggleJob = act(onFailure = {}) {
            write(tracks)
            addedTo = name
        }
    }

    private fun act(onFailure: () -> Unit, block: suspend () -> Unit): Job {
        actionError = null
        return viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure()
                actionError = e
            }
        }
    }

    private suspend fun fetchTitle(): DetailModel = fetchTitleModel(client, kind, tmdbId)

    private suspend fun fetch(): DetailModel = if (tmdbId > 0) fetchTitle() else when (kind) {
        Kind.ALBUM -> client.album(id).toModel()
        Kind.ARTIST -> client.artist(id).toModel()
        Kind.PLAYLIST -> client.playlist(id).toModel()
        Kind.AUDIOBOOK, Kind.BOOK, Kind.COMIC, Kind.COMIC_VOLUME -> client.book(id).toModel()
        else -> coroutineScope {
            val item = client.item(id)
            // The progress and the episode guide need only the item: asked for together.
            val progress = async {
                if (item.files.isEmpty()) return@async null
                try {
                    client.progress(item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            val guide = async { EpisodeGuide.fetch(client, item) }
            item.toModel(progress.await(), guide.await())
        }
    }
}

/** A movie or series by its TMDB id, as the page of a title that may not be in the library. */
private suspend fun fetchTitleModel(client: AntresolClient, kind: String, tmdbId: Long): DetailModel = coroutineScope {
    val info = client.title(kind, tmdbId)
    val item = info.asItem()
    val guide = EpisodeGuide.fetch(client, item)
    DetailModel(
        card = item.toCard(),
        byline = item.originalTitle?.takeIf { it.isNotBlank() && it != item.title }.orEmpty(),
        durationMs = item.runtime?.let { it * 60_000L },
        rating = item.rating,
        genres = item.genres,
        overview = item.overview,
        video = item.titleVideoInfo(info, guide),
    )
}

internal fun ItemDetail.titleVideoInfo(info: TitleInfo, guide: EpisodeGuideResponse?, today: LocalDate = LocalDate.now()): VideoInfo = VideoInfo(
    playTarget = null,
    canWatchlist = false,
    inWatchlist = false,
    watched = false,
    cast = cast,
    guide = EpisodeGuide.forTitle(info, guide, today),
    tmdbId = tmdbId,
    outsideLibrary = true,
    libraryItemId = info.libraryItemId?.takeIf { info.inLibrary && it > 0 },
    kidRequest = info.kidRequest,
)

private fun ItemDetail.toModel(progress: Progress?, guide: EpisodeGuideResponse?): DetailModel {
    val fileDuration = files.firstOrNull()?.durationMs
    return DetailModel(
        card = toCard(),
        byline = originalTitle?.takeIf { it.isNotBlank() && it != title }.orEmpty(),
        durationMs = runtime?.let { it * 60_000L } ?: fileDuration,
        rating = rating,
        genres = genres,
        overview = overview,
        video = videoInfo(progress, guide),
    )
}

internal fun ItemDetail.videoInfo(
    progress: Progress?,
    guide: EpisodeGuideResponse? = null,
    today: LocalDate = LocalDate.now(),
): VideoInfo {
    val episodes = Episodes.ordered(this)
    val target = when {
        files.isNotEmpty() -> PlayTarget(
            itemId = id,
            resumeMs = progress?.let { (Resume.videoStartSec(it) * 1000).toLong() } ?: 0L,
        )
        else -> Episodes.toPlay(this)?.let { episode ->
            val resume = if (episode.watched) 0L else ((episode.durationMs ?: 0L) * episode.progress).toLong()
            PlayTarget(episode.id, resume, episode.seasonNo, episode.episodeNo)
        }
    }
    val file = files.firstOrNull()
    return VideoInfo(
        playTarget = target,
        canWatchlist = kind == Kind.MOVIE || kind == Kind.SERIES,
        inWatchlist = inWatchlist,
        watched = if (files.isNotEmpty()) progress?.finished == true else episodes.isNotEmpty() && episodes.all { it.watched },
        audio = file?.audio.orEmpty(),
        subtitles = file?.subs.orEmpty(),
        cast = cast,
        guide = EpisodeGuide.build(this, guide, today),
        tmdbId = tmdbId,
    )
}

private fun AlbumDetail.toModel() = DetailModel(
    card = card,
    byline = artist?.name.orEmpty(),
    durationMs = durationMs.takeIf { it > 0 },
    genres = genres,
    songCount = trackCount.takeIf { it > 0 } ?: tracks.size,
    rating = quality.ifBlank { null },
    music = MusicInfo(tracks = tracks, tracksTitle = R.string.detail_tracks, canStar = true, starred = starred),
)

private fun ArtistDetail.toModel() = DetailModel(
    card = card,
    music = MusicInfo(
        tracks = topTracks,
        tracksTitle = TvR.string.music_top_songs,
        // The artist detail does not say whether the artist is a favorite, so no toggle is offered.
        canStar = false,
        starred = false,
        shuffle = ShuffleSource.Artist(card.id),
    ),
    rows = listOf(
        DetailRow(id = "albums", titleRes = R.string.detail_albums, cards = albums),
        DetailRow(id = "appears-on", titleRes = R.string.detail_appears_on, cards = appearsOn),
    ).filter { it.cards.isNotEmpty() },
)

private fun PlaylistDetail.toModel() = DetailModel(
    card = Card(id = playlist.id, kind = Kind.PLAYLIST, title = playlist.name, poster = playlist.cover),
    durationMs = playlist.durationMs.takeIf { it > 0 },
    songCount = playlist.trackCount,
    music = MusicInfo(tracks = tracks, tracksTitle = R.string.detail_tracks, canStar = false, starred = false),
)

private fun BookDetail.toModel() = DetailModel(
    card = card,
    byline = authors.joinToString(", "),
    narrator = narrator,
    durationMs = durationMs.takeIf { it > 0 },
    overview = description,
    rows = listOf(DetailRow(id = "volumes", titleRes = R.string.detail_volumes, cards = volumes.map { it.toCard() }))
        .filter { it.cards.isNotEmpty() },
    book = BookInfo(
        detail = this,
        want = want,
        resumeMs = Resume.audiobookStartMs(progress, durationMs),
        comic = comicTarget(),
    ),
)

/** The volume to read: this volume, or the first unfinished volume of a series. */
internal fun BookDetail.comicTarget(): ComicTarget? = when (kind) {
    Kind.COMIC_VOLUME -> if (readFileId == 0L) null else ComicTarget(
        volumeId = card.id,
        continuePage = progress?.takeIf { !it.finished && it.fraction > 0 }?.let { ComicProgress.startPage(it.locator, false, Int.MAX_VALUE) + 1 },
    )
    Kind.COMIC -> volumes.firstOrNull { it.progress < 1.0 }?.let { ComicTarget(it.id, null) }
        ?: volumes.firstOrNull()?.let { ComicTarget(it.id, null) }
    else -> null
}
