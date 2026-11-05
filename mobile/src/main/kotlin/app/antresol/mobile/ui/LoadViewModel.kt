package app.antresol.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds one thing loaded from the server. [reload] shows the spinner; [refresh] updates in place
 * when the data is older than [staleAfterMs] (so a page reflects what was just watched) and keeps
 * the shown content on failure.
 */
open class LoadViewModel<T>(
    private val staleAfterMs: Long = 30_000L,
    private val fetch: suspend () -> T,
) : ViewModel() {
    var state: LoadState<T> by mutableStateOf(LoadState.Loading)
        private set

    /** A pull-to-refresh is running. */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    private var loadedAt = 0L
    private var job: Job? = null

    init {
        reload()
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch { apply(load { fetch() }) }
    }

    /** [force]: the user pulled to refresh (shows the indicator; an error page reloads). */
    fun refresh(force: Boolean = false) {
        if (force && state is LoadState.Failed) return reload()
        if (state !is LoadState.Ready || job?.isActive == true) return
        if (!force && System.currentTimeMillis() - loadedAt < staleAfterMs) return
        refreshing = force
        job = viewModelScope.launch {
            val fresh = load { fetch() }
            if (fresh is LoadState.Ready) apply(fresh)
            refreshing = false
        }
    }

    /** Replaces the shown value without a round trip (optimistic updates). */
    protected fun update(transform: (T) -> T) {
        val ready = state as? LoadState.Ready ?: return
        state = LoadState.Ready(transform(ready.value))
    }

    private fun apply(result: LoadState<T>) {
        state = result
        if (result is LoadState.Ready) loadedAt = System.currentTimeMillis()
    }
}
