package app.antresol.tv.ui.browse

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AlbumSort
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.Page
import app.antresol.core.model.ShuffleSource
import app.antresol.ui.LoadState
import app.antresol.tv.ui.components.CardShape
import app.antresol.ui.load
import app.antresol.ui.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A sort pill of a grid: its label and the API value. */
data class GridSort(@StringRes val label: Int, val apiValue: String)

/** The libraries a [CardGridScreen] can show. */
enum class GridKind { Movies, Shows, Albums, Artists, Playlists }

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
        GridKind.Playlists -> client.playlists().items.map { playlist ->
            Card(id = playlist.id, kind = Kind.PLAYLIST, title = playlist.name, subtitle = songCount(playlist.trackCount), poster = playlist.cover)
        }.let { Page(it, it.size) }
    }
}

/** A paged grid of cards from a [GridSource], with its sort orders. */
class CardGridViewModel(
    private val client: AntresolClient,
    val source: GridSource,
    private val clock: () -> Long = System::currentTimeMillis,
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

    /** When the shown cards were read (a tab's grid is kept while the tab is away, see the shell's `tabData`). */
    private var loadedAt = 0L

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { source.page(client, sort, 0, songCount).let { Grid(it.items, it.total, it.items.size, it.items.isEmpty()) } }
            loadedAt = clock()
        }
    }

    /**
     * The grid is shown again: when what it shows is older than [staleAfterMs] (a film watched since, a new one
     * added), the pages shown are read again and replace the cards in place, without the spinner; a failure keeps them.
     */
    fun refreshIfStale(staleAfterMs: Long = STALE_AFTER_MS) {
        val shown = (state as? LoadState.Ready)?.value ?: return
        if (job?.isActive == true || clock() - loadedAt < staleAfterMs) return
        val wanted = shown.nextOffset.coerceAtMost(MAX_REFRESH_ITEMS)
        job = viewModelScope.launch {
            val fresh = load {
                val cards = ArrayList<Card>()
                val seen = HashSet<Long>()
                var offset = 0
                var total: Int
                var exhausted = false
                do {
                    val page = source.page(client, sort, offset, songCount)
                    total = page.total
                    offset += page.items.size
                    page.items.filterTo(cards) { seen.add(it.id) }
                    if (page.items.isEmpty()) exhausted = true
                } while (!exhausted && offset < wanted && offset < total)
                Grid(cards, total, offset, exhausted)
            }
            if (fresh is LoadState.Ready) {
                state = fresh
                loadedAt = clock()
            }
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

    private companion object {
        const val STALE_AFTER_MS = 30_000L

        /** A refresh reads at most this many cards again (the rest load as the grid is scrolled). */
        const val MAX_REFRESH_ITEMS = 480
    }
}
