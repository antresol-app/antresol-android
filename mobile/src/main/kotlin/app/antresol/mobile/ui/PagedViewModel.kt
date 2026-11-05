package app.antresol.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What a paged list has loaded so far. */
data class Paged<T>(val items: List<T>, val total: Int) {
    val hasMore: Boolean get() = items.size < total
}

/**
 * A list loaded page by page from the server (grids, songs). [loadMore] appends the next page and
 * remembers a failure ([pageError]) so the list offers "Try Again" instead of retrying on every
 * layout pass; [refresh] (pull to refresh) reloads what is shown in place and keeps it on failure.
 */
abstract class PagedViewModel<T> : ViewModel() {
    private var stateValue: LoadState<Paged<T>> by mutableStateOf(LoadState.Loading)
    private var pageErrorValue: Throwable? by mutableStateOf(null)

    /** Bumped whenever [state] or [pageError] changes, so [followToEnd] can wait without a UI frame. */
    private val changes = MutableStateFlow(0L)

    var state: LoadState<Paged<T>>
        get() = stateValue
        private set(value) {
            stateValue = value
            changes.value++
        }

    /** The last page failed to load; the list shows "Try Again" at its end. */
    var pageError: Throwable?
        get() = pageErrorValue
        private set(value) {
            pageErrorValue = value
            changes.value++
        }

    /** The next page is being fetched (footer spinner). */
    var loadingMore: Boolean by mutableStateOf(false)
        private set

    /** A pull-to-refresh is running. */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** One page starting at [offset]. */
    protected abstract suspend fun fetch(offset: Int): Page<T>

    /** Identity of an item, to drop duplicates when the list shifted between pages. */
    protected abstract fun keyOf(item: T): Any

    open fun reload() {
        job?.cancel()
        pageError = null
        loadingMore = false
        refreshing = false
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { fetch(0).let { Paged(it.items, it.total) } }
        }
    }

    /** Fetches the next page when the user nears the end of what is loaded; [retry] after a failed page. */
    fun loadMore(retry: Boolean = false) {
        if (pageError != null && !retry) return
        val list = (state as? LoadState.Ready)?.value ?: return
        if (!list.hasMore || job?.isActive == true) return
        pageError = null
        loadingMore = true
        job = viewModelScope.launch {
            try {
                val next = fetch(list.items.size)
                val known = list.items.mapTo(HashSet(), ::keyOf)
                val fresh = next.items.filter { keyOf(it) !in known }
                // A page without new items means the total was stale: stop paging.
                val total = if (fresh.isEmpty()) list.items.size else next.total
                state = LoadState.Ready(Paged(list.items + fresh, total))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageError = e
            } finally {
                loadingMore = false
            }
        }
    }

    /**
     * Pull to refresh: fetches as many items as are shown again (bounded) without a spinner over
     * the list; a failure keeps the current list. An error page reloads instead.
     */
    fun refresh() {
        if (state is LoadState.Failed) return reload()
        val shown = (state as? LoadState.Ready)?.value ?: return
        if (job?.isActive == true) return
        refreshing = true
        job = viewModelScope.launch {
            try {
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
                    pageError = null
                    state = fresh
                }
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * Hands every item after the first [from] to [onItems] as pages arrive, fetching the ones not
     * loaded yet, until the list is complete, it is reloaded, or [wanted] turns false. A failed page
     * is retried a few times with a growing delay, then waits for "Try Again" in the list; pages that
     * arrive later (that retry, or scrolling) are still delivered. Runs until then; cancel to stop.
     */
    protected suspend fun followToEnd(from: Int, wanted: () -> Boolean, onItems: (List<T>) -> Unit) {
        var delivered = from
        var retries = 0
        while (wanted()) {
            val seen = changes.value
            val current = (state as? LoadState.Ready)?.value ?: return
            if (current.items.size > delivered) {
                onItems(current.items.subList(delivered, current.items.size))
                delivered = current.items.size
                retries = 0
            }
            if (!current.hasMore) return
            val error = pageError
            when {
                // The page arrives or fails (scrolling may have asked for it first).
                error == null -> {
                    loadMore()
                    changes.first { it != seen }
                }
                retries < RETRY_DELAYS_MS.size -> {
                    delay(RETRY_DELAYS_MS[retries++])
                    // "Try Again" in the list may have retried it meanwhile.
                    if (pageError === error) loadMore(retry = true)
                }
                // Out of automatic retries: wait for "Try Again" (or a reload).
                else -> changes.first { it != seen }
            }
        }
    }

    private companion object {
        const val MAX_REFRESH_ITEMS = 500

        /** Automatic retries of a page [followToEnd] needs. */
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L)
    }
}
