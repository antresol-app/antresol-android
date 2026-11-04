package app.antresol.mobile.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Card
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A paged, sortable grid of cards from a [GridSource]. */
class CardGridViewModel(private val client: AntresolClient, val source: GridSource) : ViewModel() {
    data class Grid(val cards: List<Card>, val total: Int) {
        val hasMore: Boolean get() = cards.size < total
    }

    var sort: GridSort? by mutableStateOf(source.sorts.firstOrNull())
        private set

    var state: LoadState<Grid> by mutableStateOf(LoadState.Loading)
        private set

    private var job: Job? = null

    init {
        reload()
    }

    fun select(newSort: GridSort) {
        if (newSort == sort) return
        sort = newSort
        reload()
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { source.page(client, sort, 0).let { Grid(it.items, it.total) } }
        }
    }

    /** Fetches the next page when the user nears the end of what is loaded. */
    fun loadMore() {
        val grid = (state as? LoadState.Ready)?.value ?: return
        if (!grid.hasMore || job?.isActive == true) return
        job = viewModelScope.launch {
            val next = load { source.page(client, sort, grid.cards.size) }
            if (next is LoadState.Ready) {
                val known = grid.cards.mapTo(HashSet()) { "${it.kind}:${it.id}" }
                val fresh = next.value.items.filter { "${it.kind}:${it.id}" !in known }
                // A page without new cards means the total was stale: stop paging.
                val total = if (fresh.isEmpty()) grid.cards.size else next.value.total
                state = LoadState.Ready(Grid(grid.cards + fresh, total))
            }
        }
    }
}
