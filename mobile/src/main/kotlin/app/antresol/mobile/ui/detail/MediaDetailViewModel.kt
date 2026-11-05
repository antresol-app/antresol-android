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
import app.antresol.core.model.PlaylistPatch
import app.antresol.core.model.ShuffleSource
import app.antresol.core.model.Track
import app.antresol.core.music.PlaylistEdits
import app.antresol.core.playback.PendingWrites
import app.antresol.mobile.R
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LoadViewModel
import app.antresol.mobile.ui.playlists.PlaylistPicker
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

    /** "Add to Playlist" for the album or a song on this page. */
    val playlistPicker = PlaylistPicker(client, viewModelScope)

    /** Playlist pages: the songs show move and remove controls. */
    var editing: Boolean by mutableStateOf(false)
        private set

    /** A playlist edit is being saved; edits wait for it, so positions always match the server. */
    var savingPlaylist: Boolean by mutableStateOf(false)
        private set

    fun toggleEditing() {
        editing = !editing
    }

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

    /** Playlists: renames it (shown at once, undone if the server refuses). */
    fun renamePlaylist(name: String) {
        val before = (state as? LoadState.Ready)?.value ?: return
        val patch = PlaylistEdits.rename(name)
        val newName = patch.name
        if (newName.isNullOrEmpty() || newName == before.card.title) return
        editPlaylist(before, { it.copy(card = it.card.copy(title = newName)) }, patch)
    }

    /** Playlists: moves the song at [from] to [to]. */
    fun moveTrack(from: Int, to: Int) {
        val before = (state as? LoadState.Ready)?.value ?: return
        if (from == to || to !in before.tracks.indices) return
        editPlaylist(before, { it.copy(tracks = PlaylistEdits.moved(it.tracks, from, to)) }, PlaylistEdits.move(before.tracks.size, from, to))
    }

    /** Playlists: removes the song at [position]. */
    fun removeTrack(position: Int) {
        val before = (state as? LoadState.Ready)?.value ?: return
        if (position !in before.tracks.indices) return
        val removed = before.tracks[position]
        editPlaylist(
            before,
            {
                it.copy(
                    tracks = PlaylistEdits.removed(it.tracks, position),
                    songCount = it.songCount?.minus(1)?.coerceAtLeast(0),
                    durationMs = it.durationMs?.minus(removed.durationMs)?.takeIf { ms -> ms > 0 },
                )
            },
            PlaylistEdits.remove(position),
        )
    }

    /** Playlists: deletes it, then calls [onDeleted] (the page closes). */
    fun deletePlaylist(onDeleted: () -> Unit) {
        if (savingPlaylist) return
        savingPlaylist = true
        viewModelScope.launch {
            try {
                client.deletePlaylist(id)
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = R.string.detail_action_failed
            } finally {
                savingPlaylist = false
            }
        }
    }

    /** One playlist edit at a time: positions in the next edit refer to the list after this one. */
    private fun editPlaylist(before: MediaDetail, change: (MediaDetail) -> MediaDetail, patch: PlaylistPatch) {
        if (savingPlaylist) return
        savingPlaylist = true
        update(change)
        viewModelScope.launch {
            try {
                client.updatePlaylist(id, patch)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                update { before }
                notice = R.string.detail_action_failed
            } finally {
                savingPlaylist = false
            }
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
