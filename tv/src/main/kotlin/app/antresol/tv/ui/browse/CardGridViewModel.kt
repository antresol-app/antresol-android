package app.antresol.tv.ui.browse

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AlbumSort
import app.antresol.core.model.BookKind
import app.antresol.core.model.BookSort
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.Page
import app.antresol.core.model.ShuffleSource
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.CardShape
import app.antresol.tv.ui.load
import app.antresol.ui.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A sort pill of a grid: its label and the API value. */
data class GridSort(@StringRes val label: Int, val apiValue: String)

/** The libraries a [CardGridScreen] can show. */
enum class GridKind { Movies, Shows, Albums, Artists, Playlists, Books, Audiobooks, Comics }

/** What a grid lists: a library, optionally narrowed to a music [genre]. */
data class GridSource(val kind: GridKind, val genre: String? = null) {
    val sorts: List<GridSort>
        get() = when (kind) {
            GridKind.Movies, GridKind.Shows -> listOf(
                GridSort(R.string.sort_recent, "recent"),
                GridSort(R.string.sort_title, "title"),
                GridSort(R.string.sort_year, "year"),
            )
            GridKind.Albums -> listOf(
                GridSort(R.string.sort_recent, AlbumSort.ADDED),
                GridSort(R.string.sort_title, AlbumSort.TITLE),
                GridSort(R.string.sort_artist, AlbumSort.ARTIST),
                GridSort(R.string.sort_year, AlbumSort.YEAR),
            )
            GridKind.Books, GridKind.Audiobooks, GridKind.Comics -> listOf(
                GridSort(R.string.sort_recent, BookSort.ADDED),
                GridSort(R.string.sort_title, BookSort.TITLE),
                GridSort(R.string.sort_author, BookSort.AUTHOR),
            )
            GridKind.Artists, GridKind.Playlists -> emptyList()
        }

    val shape: CardShape
        get() = when (kind) {
            GridKind.Albums, GridKind.Playlists -> CardShape.Square
            GridKind.Artists -> CardShape.Round
            else -> CardShape.Poster
        }

    /** The genre grid offers "Shuffle" for the genre's songs. */
    val shuffle: ShuffleSource? get() = genre?.let { ShuffleSource.Genre(it) }

    suspend fun page(client: AntresolClient, sort: String?, offset: Int, songCount: (Int) -> String): Page<Card> = when (kind) {
        GridKind.Movies -> client.items(kind = Kind.MOVIE, sort = sort, offset = offset)
        GridKind.Shows -> client.items(kind = Kind.SERIES, sort = sort, offset = offset)
        GridKind.Albums -> client.albums(sort = sort, genre = genre, offset = offset)
        GridKind.Artists -> client.artists(offset = offset)
        GridKind.Books -> client.books(BookKind.BOOK, sort = sort, offset = offset)
        GridKind.Audiobooks -> client.books(BookKind.AUDIOBOOK, sort = sort, offset = offset)
        GridKind.Comics -> client.books(BookKind.COMIC, sort = sort, offset = offset)
        GridKind.Playlists -> client.playlists().items.map { playlist ->
            Card(id = playlist.id, kind = Kind.PLAYLIST, title = playlist.name, subtitle = songCount(playlist.trackCount), poster = playlist.cover)
        }.let { Page(it, it.size) }
    }
}

/** A paged grid of cards from a [GridSource], with its sort orders. */
class CardGridViewModel(
    private val client: AntresolClient,
    val source: GridSource,
    private val songCount: (Int) -> String,
) : ViewModel() {
    /** [nextOffset] counts what the server returned (before de-duplication), as spec 4.2 pages. */
    data class Grid(val cards: List<Card>, val total: Int, val nextOffset: Int, val exhausted: Boolean = false) {
        val hasMore: Boolean get() = !exhausted && nextOffset < total
    }

    var sortIndex: Int by mutableStateOf(0)
        private set

    var state: LoadState<Grid> by mutableStateOf(LoadState.Loading)
        private set

    private var job: Job? = null
    private val sort: String? get() = source.sorts.getOrNull(sortIndex)?.apiValue

    init {
        reload()
    }

    fun select(index: Int) {
        if (index == sortIndex) return
        sortIndex = index
        reload()
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { source.page(client, sort, 0, songCount).let { Grid(it.items, it.total, it.items.size, it.items.isEmpty()) } }
        }
    }

    /** Fetches the next page when the user nears the end of what is loaded. */
    fun loadMore() {
        val grid = (state as? LoadState.Ready)?.value ?: return
        if (!grid.hasMore || job?.isActive == true) return
        job = viewModelScope.launch {
            val next = load { source.page(client, sort, grid.nextOffset, songCount) }
            if (next is LoadState.Ready) {
                val page = next.value
                val known = grid.cards.mapTo(HashSet()) { it.id }
                state = LoadState.Ready(
                    Grid(
                        cards = grid.cards + page.items.filter { it.id !in known },
                        total = page.total,
                        nextOffset = grid.nextOffset + page.items.size,
                        exhausted = page.items.isEmpty(),
                    ),
                )
            }
        }
    }
}
