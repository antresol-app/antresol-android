package app.antresol.tv.ui.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.AudioTrack
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.CastMember
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.PlaylistDetail
import app.antresol.core.model.Progress
import app.antresol.core.model.SeasonInfo
import app.antresol.core.model.ShuffleSource
import app.antresol.core.model.SubTrack
import app.antresol.core.model.Track
import app.antresol.core.playback.Episodes
import app.antresol.core.playback.Resume
import app.antresol.core.reading.ComicProgress
import app.antresol.tv.R as TvR
import app.antresol.tv.playback.AudioContent
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.load
import app.antresol.ui.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    val seasons: List<SeasonInfo> = emptyList(),
)

/** Loads the detail of any card kind through the matching endpoint. */
class DetailViewModel(
    private val client: AntresolClient,
    private val audio: AudioEngine,
    private val kind: String,
    private val id: Long,
) : ViewModel() {
    var state: LoadState<DetailModel> by mutableStateOf(LoadState.Loading)
        private set

    /** A failed watchlist/watched action, shown under the buttons until the next action. */
    var actionError: Throwable? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /**
     * The watchlist/watched/star/want request in flight. Presses meanwhile are ignored, so add and
     * remove never race and the page always shows what the server ends up with.
     */
    private var toggleJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch { state = load { fetch() } }
    }

    /** Re-reads the page in place (back from the player); failures keep what is shown. */
    fun refresh() {
        if (state !is LoadState.Ready || job?.isActive == true || toggleJob?.isActive == true) return
        job = viewModelScope.launch {
            val fresh = load { fetch() }
            if (fresh is LoadState.Ready) state = fresh
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

    private suspend fun fetch(): DetailModel = when (kind) {
        Kind.ALBUM -> client.album(id).toModel()
        Kind.ARTIST -> client.artist(id).toModel()
        Kind.PLAYLIST -> client.playlist(id).toModel()
        Kind.AUDIOBOOK, Kind.BOOK, Kind.COMIC, Kind.COMIC_VOLUME -> client.book(id).toModel()
        else -> {
            val item = client.item(id)
            val progress = if (item.files.isNotEmpty()) runCatching { client.progress(item.id) }.getOrNull() else null
            item.toModel(progress)
        }
    }
}

private fun ItemDetail.toModel(progress: Progress?): DetailModel {
    val fileDuration = files.firstOrNull()?.durationMs
    return DetailModel(
        card = toCard(),
        byline = originalTitle?.takeIf { it.isNotBlank() && it != title }.orEmpty(),
        durationMs = runtime?.let { it * 60_000L } ?: fileDuration,
        rating = rating,
        genres = genres,
        overview = overview,
        video = videoInfo(progress),
    )
}

internal fun ItemDetail.videoInfo(progress: Progress?): VideoInfo {
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
        seasons = seasons.filter { it.episodes.isNotEmpty() }.sortedBy { it.number },
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
