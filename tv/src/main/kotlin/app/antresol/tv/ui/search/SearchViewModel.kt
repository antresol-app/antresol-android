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
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.detail.DetailRow
import app.antresol.tv.ui.load
import app.antresol.ui.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search-as-you-type over `/api/search`, debounced. */
class SearchViewModel(private val client: AntresolClient) : ViewModel() {
    var query: String by mutableStateOf("")
        private set

    /** Null while the query is blank. */
    var results: LoadState<List<DetailRow>>? by mutableStateOf(null)
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
            results = load { client.search(text.trim()).toRows() }
        }
    }

    fun retry() = onQueryChange(query)

    private companion object {
        const val DEBOUNCE_MS = 350L
    }
}

/** Result sections in the web app's order; songs open their album. */
private fun SearchResults.toRows(): List<DetailRow> = listOf(
    DetailRow("library", titleRes = R.string.search_section_library, cards = library),
    DetailRow("artists", titleRes = R.string.search_section_artists, cards = artists),
    DetailRow("albums", titleRes = R.string.search_section_albums, cards = albums),
    DetailRow("songs", titleRes = R.string.search_section_songs, cards = songs.map { it.asAlbumCard() }.distinctBy { it.id }),
    DetailRow("audiobooks", titleRes = R.string.search_section_audiobooks, cards = audiobooks),
    DetailRow("books", titleRes = R.string.search_section_books, cards = books),
    DetailRow("comics", titleRes = R.string.search_section_comics, cards = comics),
).filter { it.cards.isNotEmpty() }

private fun Track.asAlbumCard() = Card(id = albumId, kind = Kind.ALBUM, title = title, subtitle = artist, poster = cover)
