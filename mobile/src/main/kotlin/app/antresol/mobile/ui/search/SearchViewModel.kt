package app.antresol.mobile.ui.search

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
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The kind filter pills of the search page. */
enum class SearchFilter { All, Movies, Shows, Music, Books }

/** One result section: cards in a row, or songs as a list. */
data class SearchSection(val id: String, val cards: List<Card> = emptyList(), val songs: List<Track> = emptyList())

/** Splits `/api/search` results into the sections the [filter] shows, in the web app's order. */
fun SearchResults.sections(filter: SearchFilter): List<SearchSection> {
    val movies = library.filter { it.kind == Kind.MOVIE }
    val shows = library.filter { it.kind != Kind.MOVIE }
    val all = listOf(
        SearchSection(SECTION_LIBRARY, cards = library),
        SearchSection(SECTION_ARTISTS, cards = artists),
        SearchSection(SECTION_ALBUMS, cards = albums),
        SearchSection(SECTION_SONGS, songs = songs),
        SearchSection(SECTION_AUDIOBOOKS, cards = audiobooks),
        SearchSection(SECTION_BOOKS, cards = books),
        SearchSection(SECTION_COMICS, cards = comics),
    )
    val chosen = when (filter) {
        SearchFilter.All -> all
        SearchFilter.Movies -> listOf(SearchSection(SECTION_MOVIES, cards = movies))
        SearchFilter.Shows -> listOf(SearchSection(SECTION_SHOWS, cards = shows))
        SearchFilter.Music -> all.filter { it.id in setOf(SECTION_ARTISTS, SECTION_ALBUMS, SECTION_SONGS) }
        SearchFilter.Books -> all.filter { it.id in setOf(SECTION_AUDIOBOOKS, SECTION_BOOKS, SECTION_COMICS) }
    }
    return chosen.filter { it.cards.isNotEmpty() || it.songs.isNotEmpty() }
}

const val SECTION_LIBRARY = "library"
const val SECTION_MOVIES = "movies"
const val SECTION_SHOWS = "shows"
const val SECTION_ARTISTS = "artists"
const val SECTION_ALBUMS = "albums"
const val SECTION_SONGS = "songs"
const val SECTION_AUDIOBOOKS = "audiobooks"
const val SECTION_BOOKS = "books"
const val SECTION_COMICS = "comics"

/** Search-as-you-type over `/api/search`, debounced; the filter only regroups the last answer. */
class SearchViewModel(private val client: AntresolClient) : ViewModel() {
    var query: String by mutableStateOf("")
        private set

    var filter: SearchFilter by mutableStateOf(SearchFilter.All)

    /** Null while the query is blank. */
    var results: LoadState<SearchResults>? by mutableStateOf(null)
        private set

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
            results = load { client.search(text.trim()) }
        }
    }

    fun retry() = onQueryChange(query)

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
