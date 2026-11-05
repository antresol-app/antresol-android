package app.antresol.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.model.Page
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a paged list has loaded so far. */
data class Paged<T>(val items: List<T>, val total: Int) {
    val hasMore: Boolean get() = items.size < total
}

/**
 * A list loaded page by page from the server (grids, songs). [loadMore] appends the next page and
 * remembers a failure ([pagingError]) so the list can offer "Try Again" instead of silently
 * stopping; [refresh] reloads what is shown in place and keeps it on failure.
 */
abstract class PagedViewModel<T> : ViewModel() {
    var state: LoadState<Paged<T>> by mutableStateOf(LoadState.Loading)
        private set

    /** The next page is being fetched (footer spinner). */
    var loadingMore: Boolean by mutableStateOf(false)
        private set

    /** The last [loadMore] failed; cleared by the next attempt. */
    var pagingError: Throwable? by mutableStateOf(null)
        private set

    /** A pull-to-refresh is running. */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** One page starting at [offset]. */
    protected abstract suspend fun fetch(offset: Int): Page<T>

    /** Identity of an item, to drop duplicates when the list shifted between pages. */
    protected abstract fun keyOf(item: T): Any

    fun reload() {
        job?.cancel()
        loadingMore = false
        pagingError = null
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { fetch(0).let { Paged(it.items, it.total) } }
        }
    }

    /** Fetches the next page; also the retry after [pagingError]. */
    fun loadMore() {
        val list = (state as? LoadState.Ready)?.value ?: return
        if (!list.hasMore || job?.isActive == true) return
        loadingMore = true
        pagingError = null
        job = viewModelScope.launch {
            try {
                when (val next = load { fetch(list.items.size) }) {
                    is LoadState.Ready -> {
                        val known = list.items.mapTo(HashSet(), ::keyOf)
                        val fresh = next.value.items.filter { keyOf(it) !in known }
                        // A page without new items means the total was stale: stop paging.
                        val total = if (fresh.isEmpty()) list.items.size else next.value.total
                        state = LoadState.Ready(Paged(list.items + fresh, total))
                    }
                    is LoadState.Failed -> pagingError = next.error
                    LoadState.Loading -> Unit
                }
            } finally {
                loadingMore = false
            }
        }
    }

    /**
     * Fetches what is shown again (new titles, progress bars) without a spinner, as many pages as
     * were loaded (bounded); a failure keeps the current list. [pulled]: the user pulled to refresh
     * (shows the indicator); an error page reloads instead.
     */
    fun refresh(pulled: Boolean = false) {
        if (pulled && state is LoadState.Failed) return reload()
        val shown = (state as? LoadState.Ready)?.value ?: return
        if (job?.isActive == true) return
        refreshing = pulled
        job = viewModelScope.launch {
            val fresh = load {
                val items = ArrayList<T>()
                var total: Int
                do {
                    val page = fetch(items.size)
                    total = page.total
                    if (page.items.isEmpty()) break
                    items += page.items
                } while (items.size < minOf(shown.items.size, MAX_REFRESH_ITEMS) && items.size < total)
                Paged(items.distinctBy(::keyOf), maxOf(total, items.size))
            }
            if (fresh is LoadState.Ready) {
                pagingError = null
                state = fresh
            }
            refreshing = false
        }
    }

    private companion object {
        const val MAX_REFRESH_ITEMS = 500
    }
}
