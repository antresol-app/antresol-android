package app.antresol.mobile.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.Chapter
import app.antresol.core.model.Kind
import app.antresol.core.model.PlaylistDetail
import app.antresol.core.model.ShuffleSource
import app.antresol.core.model.Track
import app.antresol.core.playback.PendingWrites
import app.antresol.mobile.R
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LoadViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A row of related cards on a detail page, titled by a string resource. */
data class RelatedRow(val id: String, val titleRes: Int, val cards: List<Card>)

/** Albums, artists, playlists, books, audiobooks and comics, unified for the detail page. */
data class MediaDetail(
    val card: Card,
    val byline: String = "",
    val narrator: String = "",
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val songCount: Int? = null,
    val durationMs: Long? = null,
    val description: String = "",
    val tracks: List<Track> = emptyList(),
    /** Playlists list songs from many albums: show the artist on each row. */
    val tracksShowArtist: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val rows: List<RelatedRow> = emptyList(),
    /** Books, audiobooks and comic series: the "want" flag (watchlist API); null = not offered. */
    val want: Boolean? = null,
    /** The book behind a book, audiobook or comic page (files, progress, volumes). */
    val book: BookDetail? = null,
    /** Albums: the favorite flag; null = not offered. */
    val starred: Boolean? = null,
)

/** Loads the detail of a music or book card through the matching endpoint. */
class MediaDetailViewModel(private val client: AntresolClient, kind: String, private val id: Long, pendingWrites: PendingWrites) :
    LoadViewModel<MediaDetail>(pendingWrites = pendingWrites, fetch = { fetchDetail(client, kind, id) }) {

    /** A string resource to show once (a failed write or playback request); the screen calls [consumeNotice]. */
    var notice: Int? by mutableStateOf(null)
        private set

    /** An artist shuffle is being fetched (repeated taps are ignored). */
    var shuffling: Boolean by mutableStateOf(false)
        private set

    fun consumeNotice() {
        notice = null
    }

    /** Artist pages: shuffle everything by the artist (`/api/music/shuffle?artistId=`). */
    fun shuffleArtist(play: (List<Track>) -> Unit) {
        if (shuffling) return
        shuffling = true
        viewModelScope.launch {
            try {
                val tracks = client.shuffle(ShuffleSource.Artist(id)).tracks
                if (tracks.isEmpty()) notice = R.string.music_nothing_to_play else play(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = R.string.music_playback_failed
            } finally {
                shuffling = false
            }
        }
    }

    fun toggleWant() {
        val before = (state as? LoadState.Ready)?.value ?: return
        val want = before.want ?: return
        mutate(before, { it.copy(want = !want) }) {
            if (want) client.removeFromWatchlist(id) else client.addToWatchlist(id)
        }
    }

    /** Albums: favorite or unfavorite (`/api/music/star/{albumId}`). */
    fun toggleStar() {
        val before = (state as? LoadState.Ready)?.value ?: return
        val starred = before.starred ?: return
        mutate(before, { it.copy(starred = !starred) }) {
            if (starred) client.unstar(id) else client.star(id)
        }
    }

    private fun mutate(before: MediaDetail, change: (MediaDetail) -> MediaDetail, write: suspend () -> Unit) {
        update(change)
        viewModelScope.launch {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { before }
                notice = R.string.detail_action_failed
            }
        }
    }

    private companion object {
        suspend fun fetchDetail(client: AntresolClient, kind: String, id: Long): MediaDetail = when (kind) {
            Kind.ALBUM -> client.album(id).toDetail()
            Kind.ARTIST -> client.artist(id).toDetail()
            Kind.PLAYLIST -> client.playlist(id).toDetail()
            else -> client.book(id).toDetail()
        }
    }
}

private fun AlbumDetail.toDetail() = MediaDetail(
    card = card,
    byline = artist?.name.orEmpty(),
    year = year.takeIf { it > 0 } ?: card.year,
    genres = genres,
    songCount = trackCount.takeIf { it > 0 } ?: tracks.size,
    durationMs = durationMs.takeIf { it > 0 },
    tracks = tracks,
    starred = starred,
)

private fun ArtistDetail.toDetail() = MediaDetail(
    card = card,
    tracks = topTracks,
    rows = listOf(
        RelatedRow("albums", app.antresol.ui.R.string.detail_albums, albums),
        RelatedRow("appears-on", app.antresol.ui.R.string.detail_appears_on, appearsOn),
    ).filter { it.cards.isNotEmpty() },
)

private fun PlaylistDetail.toDetail() = MediaDetail(
    card = Card(id = playlist.id, kind = Kind.PLAYLIST, title = playlist.name, poster = playlist.cover),
    songCount = playlist.trackCount,
    durationMs = playlist.durationMs.takeIf { it > 0 },
    tracks = tracks,
    tracksShowArtist = true,
)

private fun BookDetail.toDetail(): MediaDetail {
    val isVolume = card.kind == Kind.COMIC_VOLUME
    return MediaDetail(
        card = card,
        byline = authors.joinToString(", "),
        narrator = narrator,
        year = year.takeIf { it > 0 } ?: card.year,
        durationMs = durationMs.takeIf { it > 0 },
        description = description,
        chapters = chapters,
        rows = listOf(RelatedRow("volumes", app.antresol.ui.R.string.detail_volumes, volumes.map { it.toCard() }))
            .filter { it.cards.isNotEmpty() },
        want = if (isVolume) null else want,
        book = this,
    )
}
