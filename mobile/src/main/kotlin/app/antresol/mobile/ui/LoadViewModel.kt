package app.antresol.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.playback.PendingWrites
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Holds one thing loaded from the server. [reload] shows the spinner; [refresh] updates in place
 * when the data is older than [staleAfterMs] (so a page reflects what was just watched) and keeps
 * the shown content on failure.
 *
 * With [pendingWrites] (pages that show progress), a refresh first waits for the final writes of
 * closed players and readers, and every finished write refreshes the page even when it is fresh.
 */
open class LoadViewModel<T>(
    private val staleAfterMs: Long = 30_000L,
    private val pendingWrites: PendingWrites? = null,
    private val fetch: suspend () -> T,
) : ViewModel() {
    var state: LoadState<T> by mutableStateOf(LoadState.Loading)
        private set

    private var loadedAt = 0L
    private var job: Job? = null

    init {
        reload()
        if (pendingWrites != null) {
            viewModelScope.launch {
                pendingWrites.completions.drop(1).collect {
                    job?.join()
                    refresh(force = true)
                }
            }
        }
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch { apply(load { fetch() }) }
    }

    fun refresh(force: Boolean = false) {
        if (state !is LoadState.Ready || job?.isActive == true) return
        if (!force && System.currentTimeMillis() - loadedAt < staleAfterMs) return
        job = viewModelScope.launch {
            pendingWrites?.await()
            val fresh = load { fetch() }
            if (fresh is LoadState.Ready) apply(fresh)
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
