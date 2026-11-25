package app.antresol.mobile.ui.search

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
import app.antresol.ui.LoadState
import app.antresol.ui.load
import app.antresol.ui.requests.KidAskSearch
import app.antresol.ui.requests.TitleRequest
import app.antresol.ui.ways.AddonTitleSearch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The kind filter pills of the search page. */
enum class SearchFilter {
    All, Movies, Shows, Music, Books;

    /** The kinds of the kid's catalogue search this filter shows: movies and series, music, or the books' three. */
    val askKinds: List<String> get() = AskCards.searchKinds(video = this != Music && this != Books, music = this == Music, books = this == Books)

    /** The titles of the kid's search this filter shows (Movies and Shows narrow the movies and series). */
    fun shows(candidates: List<KidCandidate>): List<KidCandidate> = when (this) {
        Movies -> candidates.filter { it.kind == Kind.MOVIE }
        Shows -> candidates.filter { it.kind == Kind.SERIES }
        else -> candidates
    }
}

/** One result section: cards in a row, or songs as a list. [label] is the section's own title (an addon's), else the screen names it by [id]. */
data class SearchSection(
    val id: String,
    val cards: List<Card> = emptyList(),
    val songs: List<Track> = emptyList(),
    val label: String? = null,
)

/**
 * The sections the addons' searches add after the library's, for [filter]: movies and shows only
 * (what an addon finds are titles), the movies or the shows of each when the filter says so.
 */
fun List<AddonSection>.sections(filter: SearchFilter): List<SearchSection> {
    val kinds = when (filter) {
        SearchFilter.All -> null
        SearchFilter.Movies -> setOf(Kind.MOVIE)
        SearchFilter.Shows -> setOf(Kind.SERIES)
        SearchFilter.Music, SearchFilter.Books -> return emptyList()
    }
    return mapNotNull { section ->
        val titles = section.titles.filter { kinds == null || it.kind in kinds }
        titles.takeIf { it.isNotEmpty() }?.let { SearchSection("addon-${section.addonId}", cards = it.map { title -> title.toCard() }, label = section.label) }
    }
}

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
class SearchViewModel(
    private val client: AntresolClient,
    waysSource: WaysSource? = null,
    /** A kid profile with requests open: the server's catalogue search adds a section of titles to ask a parent for. */
    canAsk: Boolean = false,
) : ViewModel() {
    var query: String by mutableStateOf("")
        private set

    var filter: SearchFilter by mutableStateOf(SearchFilter.All)
        private set

    fun selectFilter(value: SearchFilter) {
        filter = value
        asks?.want(value.askKinds)
    }

    private val requests = client.requestsApi()
    private val asks = if (canAsk) KidAskSearch(requests, viewModelScope) else null

    /** The titles the kid may ask a parent for, for the page's filter; empty for anyone else. */
    val askCandidates: List<KidCandidate> get() = asks?.let { filter.shows(it.candidates(filter.askKinds)) }.orEmpty()

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

    /** Null while the query is blank. */
    var results: LoadState<SearchResults>? by mutableStateOf(null)
        private set

    private val addons = AddonTitleSearch(client.waysApi(), waysSource, viewModelScope)

    /** What the addons' searches found, after the library's results; they arrive one addon at a time, and never fail the page. */
    val addonSections: List<AddonSection> get() = addons.sections

    /** An addon's search is still running: an empty page is not «no results» yet. */
    val addonsSearching: Boolean get() = addons.searching

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
            results = load { client.search(text.trim()) }
        }
        addons.start(text.trim(), DEBOUNCE_MS)
        asks?.start(text.trim(), filter.askKinds, DEBOUNCE_MS)
    }

    fun retry() = onQueryChange(query)

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
