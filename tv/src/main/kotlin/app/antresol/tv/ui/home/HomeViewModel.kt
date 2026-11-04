package app.antresol.tv.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Card
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.Kind
import app.antresol.core.model.Shelf
import app.antresol.core.model.ShelfId
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the home screen shows: the hero rotation and the non-empty `/api/home` shelves in server order. */
data class HomeContent(
    val hero: List<Card>,
    /** Title of the shelf the hero was borrowed from when the server sent no hero; null = "New in Library". */
    val heroSource: String?,
    val shelves: List<Shelf>,
) {
    val isEmpty: Boolean get() = hero.isEmpty() && shelves.isEmpty()

    companion object {
        private const val MAX_HERO = 6
        private val VIDEO_KINDS = setOf(Kind.MOVIE, Kind.SERIES, Kind.EPISODE)
        private val HERO_FALLBACK_SHELVES = listOf(ShelfId.CONTINUE, ShelfId.RECENT_MOVIES, ShelfId.RECENT_SERIES, ShelfId.WATCHLIST)

        fun from(response: HomeResponse): HomeContent {
            val shelves = response.shelves.filter { it.cards.isNotEmpty() }
            if (response.hero.isNotEmpty()) return HomeContent(response.hero.take(MAX_HERO), null, shelves)
            val fallback = HERO_FALLBACK_SHELVES.firstNotNullOfOrNull { id ->
                shelves.firstOrNull { it.id == id }?.let { shelf ->
                    shelf.cards.firstOrNull { it.kind in VIDEO_KINDS }?.let { shelf to it }
                }
            }
            return HomeContent(listOfNotNull(fallback?.second), fallback?.first?.title, shelves)
        }
    }
}

class HomeViewModel(private val client: AntresolClient) : ViewModel() {
    var state: LoadState<HomeContent> by mutableStateOf(LoadState.Loading)
        private set

    private var loadedAt = 0L
    private var job: Job? = null

    init {
        reload()
    }

    /** Loads from scratch, showing the spinner (first load and "Try Again"). */
    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch { fetch() }
    }

    /**
     * Refreshes in place when the screen comes back and the data is older than [maxAgeMs], so
     * Continue Watching reflects what was just played. Failures keep the shown content.
     */
    fun refreshIfStale(maxAgeMs: Long = STALE_AFTER_MS) {
        if (state !is LoadState.Ready || job?.isActive == true) return
        if (System.currentTimeMillis() - loadedAt < maxAgeMs) return
        job = viewModelScope.launch {
            val fresh = load { HomeContent.from(client.home()) }
            if (fresh is LoadState.Ready) {
                state = fresh
                loadedAt = System.currentTimeMillis()
            }
        }
    }

    private suspend fun fetch() {
        state = load { HomeContent.from(client.home()) }
        if (state is LoadState.Ready) loadedAt = System.currentTimeMillis()
    }

    private companion object {
        const val STALE_AFTER_MS = 30_000L
    }
}
