package app.antresol.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.playback.PendingWrites
import kotlinx.coroutines.CancellationException
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

    /** A pull-to-refresh is running. */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    private var loadedAt = 0L
    private var job: Job? = null

    /** An [optimistic] change and whether the server took it. */
    private inner class Write(val change: (T) -> T, var accepted: Boolean = false)

    /** [optimistic] writes in flight, those that overlapped (in the order made), the value before the first, and whether one failed. */
    private var writes = 0
    private val overlapping = ArrayList<Write>()
    private var beforeWrites: T? = null
    private var writeFailed = false

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
        refreshing = false
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

    /** Pull to refresh: shows the indicator while it runs; an error page reloads instead. */
    fun pullToRefresh() {
        if (state is LoadState.Failed) return reload()
        if (state !is LoadState.Ready || job?.isActive == true) return
        refreshing = true
        job = viewModelScope.launch {
            try {
                val fresh = load { fetch() }
                if (fresh is LoadState.Ready) apply(fresh)
            } finally {
                refreshing = false
            }
        }
    }

    /** Replaces the shown value without a round trip (optimistic updates). */
    protected fun update(transform: (T) -> T) {
        val ready = state as? LoadState.Ready ?: return
        state = LoadState.Ready(transform(ready.value))
    }

    /**
     * Shows [change] at once and sends [write]; a failed write undoes the change and calls
     * [onFailure]. When several overlap and one fails, the page shows the value before the first of
     * them with the accepted changes made again, and is then re-read: a snapshot taken per write
     * would also wipe a later change that the server accepted, and so would going back to the first
     * value whenever the re-read does not happen (another load is running) or fails.
     */
    protected fun optimistic(change: (T) -> T, onFailure: () -> Unit, write: suspend () -> Unit) {
        val ready = state as? LoadState.Ready ?: return
        if (writes++ == 0) {
            beforeWrites = ready.value
            overlapping.clear()
            writeFailed = false
        }
        val made = Write(change).also(overlapping::add)
        update(change)
        viewModelScope.launch {
            try {
                write()
                made.accepted = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                writeFailed = true
                onFailure()
            } finally {
                if (--writes == 0 && writeFailed) {
                    beforeWrites?.let { before -> update { overlapping.filter { it.accepted }.fold<Write, T>(before) { value, write -> write.change(value) } } }
                    if (overlapping.size > 1) refresh(force = true)
                }
            }
        }
    }

    private fun apply(result: LoadState<T>) {
        state = result
        if (result is LoadState.Ready) loadedAt = System.currentTimeMillis()
    }
}
