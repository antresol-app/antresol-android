package app.antresol.ui.channels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.channels.ChannelSection
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The channels of the TV tab (and the phone's «On Air Now» shelf), by section. While a page shows
 * them ([startRefreshing]) the list is reloaded every [Channels.REFRESH_MS] so what is on air stays
 * current; a failed refresh keeps the channels shown. [section] is the picked category, null for «All».
 */
class ChannelsViewModel(
    private val fetch: suspend () -> List<Channel>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** Every section, or null until the first load succeeded. */
    var sections: List<ChannelSection>? by mutableStateOf(null)
        private set

    /** Why the last load failed while nothing is shown (the page offers Try Again), else null. */
    var error: Throwable? by mutableStateOf(null)
        private set

    var section: String? by mutableStateOf(null)
        private set

    /** A pull-to-refresh is running (the phone). */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    private var loadedAt = 0L
    private var job: Job? = null
    private var refreshJob: Job? = null

    init {
        reload()
    }

    /** The picked section while it exists, else null («All»). */
    val selectedSection: String?
        get() = section?.takeIf { wanted -> sections.orEmpty().any { it.category == wanted } }

    /** The sections to show: the picked one, or all of them. */
    val shown: List<ChannelSection>
        get() {
            val all = sections.orEmpty()
            val picked = selectedSection ?: return all
            return all.filter { it.category == picked }
        }

    /** The channels with something on air, by number (the phone's home shelf). */
    val onAir: List<Channel>
        get() = sections.orEmpty().flatMap { it.channels }.filter { it.now != null }.let(Channels::sortedByNumber)

    fun select(category: String?) {
        section = category
    }

    /** Loads from scratch (the spinner shows when nothing is loaded yet). */
    fun reload() {
        job?.cancel()
        error = null
        job = viewModelScope.launch { fetchNow() }
    }

    /** Pull to refresh: reloads in place, keeping the channels shown. */
    fun pullToRefresh() {
        if (job?.isActive == true) return
        refreshing = true
        job = viewModelScope.launch {
            try {
                fetchNow()
            } finally {
                refreshing = false
            }
        }
    }

    /** A page shows the channels: refresh now when stale, then every [Channels.REFRESH_MS]. */
    fun startRefreshing() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            while (isActive) {
                val wait = Channels.REFRESH_MS - (clock() - loadedAt)
                if (wait > 0) delay(wait)
                job?.takeIf { it.isActive }?.join() ?: fetchNow()
            }
        }
    }

    fun stopRefreshing() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private suspend fun fetchNow() {
        try {
            val channels = fetch()
            sections = Channels.sections(channels)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep what is shown; an error replaces only an empty page.
            if (sections == null) error = e
        } finally {
            loadedAt = clock()
        }
    }
}
