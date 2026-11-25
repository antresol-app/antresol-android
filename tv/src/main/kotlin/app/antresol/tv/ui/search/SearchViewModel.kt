package app.antresol.tv.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.SearchResults
import app.antresol.core.model.Track
import app.antresol.core.model.toCard
import app.antresol.core.requests.AskCards
import app.antresol.core.requests.requestsApi
import app.antresol.core.ways.AddonSection
import app.antresol.core.ways.WaysSource
import app.antresol.core.ways.waysApi
import app.antresol.tv.playback.AudioEngine
import app.antresol.ui.LoadState
import app.antresol.tv.ui.detail.DetailRow
import app.antresol.ui.load
import app.antresol.ui.R
import app.antresol.ui.requests.KidAskSearch
import app.antresol.ui.requests.TitleRequest
import app.antresol.ui.ways.AddonTitleSearch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search-as-you-type over `/api/search`, debounced. Songs play in place; the other results open
 * their pages. The server's book results are not shown: the TV app has no books (as on Apple TV).
 */
class SearchViewModel(
    private val client: AntresolClient,
    private val audio: AudioEngine,
    /** The addons' ways of the session: their title searches add rows after the library's. */
    waysSource: WaysSource? = null,
    /** A kid profile with requests open: the server's catalogue search adds a row of titles to ask a parent for. */
    canAsk: Boolean = false,
) : ViewModel() {
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
        asks?.want(value.askKinds)
    }

    /** The songs of the shown results, in row order (the songs row's card ids are track ids). */
    private var songs: List<Track> = emptyList()

    private val addons = AddonTitleSearch(client.waysApi(), waysSource, viewModelScope)

    /** What the addons' searches found (a row each, titled by the addon's own words), as they answer; never an error. */
    val addonRows: List<DetailRow> get() = addons.sections.map { it.toRow() }

    /** An addon's search is still running: an empty page is not «no results» yet. */
    val addonsSearching: Boolean get() = addons.searching

    private val requests = client.requestsApi()
    private val asks = if (canAsk) KidAskSearch(requests, viewModelScope) else null

    /** The titles the kid may ask a parent for, for the page's scope; empty for anyone else. */
    val askCandidates: List<KidCandidate> get() = asks?.let { scope.shows(it.candidates(scope.askKinds)) }.orEmpty()

    /** The kid's search is still looking: an empty page is not «no results» yet. */
    val asksSearching: Boolean get() = asks?.searching == true

    /** The kid's search could not be made (requests closed, the catalogue down). */
    val asksFailed: Boolean get() = asks?.failed == true

    /** The sheet of a result that has no page (an album, a book): ask a parent from it. */
    val sheet: TitleRequest? = asks?.let { found -> TitleRequest(requests, viewModelScope, onAsked = found::put) }
    var sheetOf: KidCandidate? by mutableStateOf(null)
        private set

    fun openSheet(candidate: KidCandidate) {
        sheetOf = candidate
        sheet?.load(candidate.kidRequest)
    }

    fun closeSheet() {
        sheetOf = null
    }

    /** Back on the page: the kid's requests may have changed (asked on a title page). */
    fun refreshAsks() = asks?.refresh()

    private var job: Job? = null

    fun onQueryChange(text: String) {
        query = text
        job?.cancel()
        if (text.isBlank()) {
            results = null
            addons.clear()
            asks?.clear()
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            if (results == null) results = LoadState.Loading
            results = load {
                client.search(text.trim()).also { songs = it.songs }.toRows()
            }
        }
        addons.start(text.trim(), DEBOUNCE_MS)
        asks?.start(text.trim(), scope.askKinds, DEBOUNCE_MS)
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
    All, Movies, Shows, Music;

    /** The rows of the addons' searches this scope shows: movies and shows only (they find titles). */
    fun filterAddons(rows: List<DetailRow>): List<DetailRow> = when (this) {
        All -> rows
        Movies -> rows.map { row -> row.copy(cards = row.cards.filter { it.kind == Kind.MOVIE }) }
        Shows -> rows.map { row -> row.copy(cards = row.cards.filter { it.kind == Kind.SERIES }) }
        Music -> emptyList()
    }.filter { it.cards.isNotEmpty() }

    /** The kinds of the kid's catalogue search this scope shows: movies and series, or music. */
    val askKinds: List<String> get() = AskCards.searchKinds(video = this != Music, music = this == Music)

    /** The titles of the kid's search this scope shows (Movies and Shows narrow the movies and series). */
    fun shows(candidates: List<KidCandidate>): List<KidCandidate> = when (this) {
        All, Music -> candidates
        Movies -> candidates.filter { it.kind == Kind.MOVIE }
        Shows -> candidates.filter { it.kind == Kind.SERIES }
    }

    /** The rows (and, for Movies / Shows, the cards of the library row) this scope shows. */
    fun filter(rows: List<DetailRow>): List<DetailRow> = when (this) {
        All -> rows
        Movies -> rows.filter { it.id == LIBRARY_ROW }.map { row -> row.copy(cards = row.cards.filter { it.kind == Kind.MOVIE }) }
        Shows -> rows.filter { it.id == LIBRARY_ROW }.map { row -> row.copy(cards = row.cards.filter { it.kind in SHOW_KINDS }) }
        Music -> rows.filter { it.id in MUSIC_ROWS }
    }.filter { it.cards.isNotEmpty() }

    private companion object {
        val SHOW_KINDS = setOf(Kind.SERIES, Kind.SEASON, Kind.EPISODE)
        val MUSIC_ROWS = setOf("artists", "albums", SONGS_ROW)
    }
}

private const val LIBRARY_ROW = "library"

private fun AddonSection.toRow() = DetailRow(id = "addon-$addonId", title = label, cards = titles.map { it.toCard() })

/** Result sections in the web app's order. */
private fun SearchResults.toRows(): List<DetailRow> = listOf(
    DetailRow(LIBRARY_ROW, titleRes = R.string.search_section_library, cards = library),
    DetailRow("artists", titleRes = R.string.search_section_artists, cards = artists),
    DetailRow("albums", titleRes = R.string.search_section_albums, cards = albums),
    DetailRow(SONGS_ROW, titleRes = R.string.search_section_songs, cards = songs.map { it.asSongCard() }),
).filter { it.cards.isNotEmpty() }

/** A square card for a song: its own id, album artwork. */
private fun Track.asSongCard() = Card(id = id, kind = Kind.ALBUM, title = title, subtitle = artist, poster = cover)
