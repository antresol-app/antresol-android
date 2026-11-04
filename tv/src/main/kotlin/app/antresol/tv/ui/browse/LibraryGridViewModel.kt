package app.antresol.tv.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Card
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Sort orders of `GET /api/items`. */
enum class LibrarySort(val apiValue: String) { Recent("recent"), Title("title"), Year("year") }

/** A paged grid of movies or series ([kind]) with the web app's three sort orders. */
class LibraryGridViewModel(private val client: AntresolClient, private val kind: String) : ViewModel() {
    data class Grid(val cards: List<Card>, val total: Int) {
        val hasMore: Boolean get() = cards.size < total
    }

    var sort: LibrarySort by mutableStateOf(LibrarySort.Recent)
        private set

    var state: LoadState<Grid> by mutableStateOf(LoadState.Loading)
        private set

    private var job: Job? = null

    init {
        reload()
    }

    fun select(newSort: LibrarySort) {
        if (newSort == sort) return
        sort = newSort
        reload()
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { client.items(kind = kind, sort = sort.apiValue).let { Grid(it.items, it.total) } }
        }
    }

    /** Fetches the next page when the user nears the end of what is loaded. */
    fun loadMore() {
        val grid = (state as? LoadState.Ready)?.value ?: return
        if (!grid.hasMore || job?.isActive == true) return
        job = viewModelScope.launch {
            val next = load { client.items(kind = kind, sort = sort.apiValue, offset = grid.cards.size) }
            if (next is LoadState.Ready) {
                val known = grid.cards.mapTo(HashSet()) { it.id }
                state = LoadState.Ready(Grid(grid.cards + next.value.items.filter { it.id !in known }, next.value.total))
            }
        }
    }
}
