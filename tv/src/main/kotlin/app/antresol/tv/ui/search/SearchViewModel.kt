package app.antresol.tv.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.SearchResults
import app.antresol.core.model.Track
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.detail.DetailRow
import app.antresol.tv.ui.load
import app.antresol.ui.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search-as-you-type over `/api/search`, debounced. Songs play in place; the other results open their pages. */
class SearchViewModel(private val client: AntresolClient, private val audio: AudioEngine) : ViewModel() {
    var query: String by mutableStateOf("")
        private set

    /** Null while the query is blank. */
    var results: LoadState<List<DetailRow>>? by mutableStateOf(null)
        private set

    /** Which kinds of results to show (filtered here: `/api/search` has no kind parameter). */
    var scope: SearchScope by mutableStateOf(SearchScope.All)
        private set

    fun selectScope(value: SearchScope) {
        scope = value
    }

    /** The songs of the shown results, in row order (the songs row's card ids are track ids). */
    private var songs: List<Track> = emptyList()

    private var job: Job? = null

    fun onQueryChange(text: String) {
        query = text
        job?.cancel()
        if (text.isBlank()) {
            results = null
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            if (results == null) results = LoadState.Loading
            results = load {
                client.search(text.trim()).also { songs = it.songs }.toRows()
            }
        }
    }

    /** Plays the found songs starting at [card]'s track. */
    fun playSong(card: Card, onStarted: () -> Unit) {
        val index = songs.indexOfFirst { it.id == card.id }
        if (index < 0) return
        if (audio.playTracks(songs, index) != null) onStarted()
    }

    /** The album page of [card]'s track (long press on a song). */
    fun songAlbum(card: Card): Card? = songs.firstOrNull { it.id == card.id }?.let {
        Card(id = it.albumId, kind = Kind.ALBUM, title = it.album, subtitle = it.artist, poster = it.cover)
    }

    fun retry() = onQueryChange(query)

    private companion object {
        const val DEBOUNCE_MS = 350L
    }
}

const val SONGS_ROW = "songs"

/** The scope pills of the Search page. */
enum class SearchScope {
    All, Movies, Shows, Music, Books;

    /** The rows (and, for Movies / TV Shows, the cards of the library row) this scope shows. */
    fun filter(rows: List<DetailRow>): List<DetailRow> = when (this) {
        All -> rows
        Movies -> rows.filter { it.id == LIBRARY_ROW }.map { row -> row.copy(cards = row.cards.filter { it.kind == Kind.MOVIE }) }
        Shows -> rows.filter { it.id == LIBRARY_ROW }.map { row -> row.copy(cards = row.cards.filter { it.kind in SHOW_KINDS }) }
        Music -> rows.filter { it.id in MUSIC_ROWS }
        Books -> rows.filter { it.id in BOOK_ROWS }
    }.filter { it.cards.isNotEmpty() }

    private companion object {
        val SHOW_KINDS = setOf(Kind.SERIES, Kind.SEASON, Kind.EPISODE)
        val MUSIC_ROWS = setOf("artists", "albums", SONGS_ROW)
        val BOOK_ROWS = setOf("audiobooks", "books", "comics")
    }
}

private const val LIBRARY_ROW = "library"

/** Result sections in the web app's order. */
private fun SearchResults.toRows(): List<DetailRow> = listOf(
    DetailRow(LIBRARY_ROW, titleRes = R.string.search_section_library, cards = library),
    DetailRow("artists", titleRes = R.string.search_section_artists, cards = artists),
    DetailRow("albums", titleRes = R.string.search_section_albums, cards = albums),
    DetailRow(SONGS_ROW, titleRes = R.string.search_section_songs, cards = songs.map { it.asSongCard() }),
    DetailRow("audiobooks", titleRes = R.string.search_section_audiobooks, cards = audiobooks),
    DetailRow("books", titleRes = R.string.search_section_books, cards = books),
    DetailRow("comics", titleRes = R.string.search_section_comics, cards = comics),
).filter { it.cards.isNotEmpty() }

/** A square card for a song: its own id, album artwork. */
private fun Track.asSongCard() = Card(id = id, kind = Kind.ALBUM, title = title, subtitle = artist, poster = cover)
