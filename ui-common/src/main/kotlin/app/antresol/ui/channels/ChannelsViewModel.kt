package app.antresol.ui.channels

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.channels.Epg
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The server calls of the «ТВ» page; [AntresolClient.channelsApi] is the real one. */
interface ChannelsApi {
    suspend fun channels(): ChannelList

    suspend fun setFavorite(channelId: Long, favorite: Boolean)

    suspend fun epg(fromIso: String, hours: Int): ChannelEpg

    /** `POST /api/channels/opened`: «ТВ» opened (the server refreshes «при открытии» sources). */
    suspend fun opened() {}
}

fun AntresolClient.channelsApi(): ChannelsApi = object : ChannelsApi {
    override suspend fun channels() = this@channelsApi.channels()

    override suspend fun setFavorite(channelId: Long, favorite: Boolean) = setChannelFavorite(channelId, favorite)

    override suspend fun epg(fromIso: String, hours: Int) = channelEpg(fromIso, hours)

    override suspend fun opened() = channelsOpened()
}

/** «Каналы | Телегид». */
enum class ChannelsView { Channels, Guide }

/**
 * The «ТВ» page: ONE list of channels by number (like a television), narrowed by a [filter] («All»,
 * «Favourites», a theme), and the programme guide of the same list. While a page shows it
 * ([startRefreshing]) the list is reloaded every [Channels.REFRESH_MS]; a failed refresh keeps what
 * is shown. Favourites are written to the server at once (and undone when it refuses).
 * [lastChannel] is the profile's last channel (the server's): the initial focus and «Back to N».
 */
class ChannelsViewModel(
    private val api: ChannelsApi,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** Every channel by number, or null until the first load succeeded. */
    var channels: List<Channel>? by mutableStateOf(null)
        private set

    /** Why the last load failed while nothing is shown (the page offers Try Again), else null. */
    var error: Throwable? by mutableStateOf(null)
        private set

    var requestedFilter: ChannelFilter by mutableStateOf(ChannelFilter.All)
        private set

    var view: ChannelsView by mutableStateOf(ChannelsView.Channels)

    /** A pull-to-refresh is running (the phone). */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    /** The profile's last channel id from the server. */
    var lastChannelId: Long? by mutableStateOf(null)
        private set

    /** The guide's rows (the whole list; the page filters them), null until its first page loaded. */
    var guideRows: List<ChannelEpgRow>? by mutableStateOf(null)
        private set

    var guideError: Throwable? by mutableStateOf(null)
        private set

    /** Where the guide's timeline starts and how far it is loaded. */
    var guideFromMs: Long by mutableStateOf(Epg.origin(clock()))
        private set
    var guideToMs: Long by mutableStateOf(guideFromMs)
        private set

    private var loadedAt = 0L
    private var job: Job? = null
    private var refreshJob: Job? = null
    private var guideJob: Job? = null

    // The lists below are derived state: worked out once per change of what they read, not on every
    // read from a composition (each key press and clock tick), and the same instances until then.

    /** The filter while it still means something (a theme that is gone falls back to «All»). */
    val filter: ChannelFilter by derivedStateOf { Channels.validFilter(channels.orEmpty(), requestedFilter) }

    /** The themes for the filter row. */
    val themes: List<String> by derivedStateOf { Channels.themes(channels.orEmpty()) }

    /** The channels of the [filter], by number. */
    val shown: List<Channel> by derivedStateOf { Channels.filter(channels.orEmpty(), filter) }

    /** The last channel while the profile can still see it. */
    val lastChannel: Channel? by derivedStateOf { Channels.lastChannel(channels.orEmpty(), lastChannelId) }

    /** The channels with something on air, by number (the phone's home shelf). */
    val onAir: List<Channel> by derivedStateOf { Channels.sortedByNumber(channels.orEmpty().filter { it.now != null }) }

    /** The guide rows of the [filter], in list order. */
    val shownGuide: List<ChannelEpgRow> by derivedStateOf {
        val ids = shown.map { it.id }.toSet()
        val byId = channels.orEmpty().associateBy { it.id }
        guideRows.orEmpty().filter { it.id in ids }.map { row ->
            val channel = byId[row.id]
            val favorite = channel?.favorite ?: row.favorite
            val logoUrl = row.logoUrl ?: channel?.logoUrl
            if (favorite == row.favorite && logoUrl == row.logoUrl) row else row.copy(favorite = favorite, logoUrl = logoUrl)
        }
    }

    /** The pages showing the list ([startRefreshing]); the refresh stops with the last one. */
    private val watchers = HashSet<Any>()

    init {
        reload()
    }

    fun select(filter: ChannelFilter) {
        requestedFilter = filter
    }

    fun showView(next: ChannelsView) {
        view = next
        if (next == ChannelsView.Guide && guideRows == null && guideJob?.isActive != true) loadGuide()
    }

    /** Favourite on/off for [channel]: shown at once, sent to the server, undone if refused. */
    fun toggleFavorite(channel: Channel) {
        val favorite = !(channels?.firstOrNull { it.id == channel.id }?.favorite ?: channel.favorite)
        setFavoriteLocally(channel.id, favorite)
        viewModelScope.launch {
            try {
                api.setFavorite(channel.id, favorite)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setFavoriteLocally(channel.id, !favorite)
            }
        }
    }

    /** The player tuned in [channelId] (it also wrote it on the server): highlight it on return. */
    fun tunedIn(channelId: Long) {
        lastChannelId = channelId
    }

    private fun setFavoriteLocally(channelId: Long, favorite: Boolean) {
        channels = channels?.map { if (it.id == channelId) it.copy(favorite = favorite) else it }
    }

    /** Loads from scratch (the spinner shows when nothing is loaded yet). */
    fun reload() {
        job?.cancel()
        error = null
        job = viewModelScope.launch { fetchNow() }
        if (guideRows != null || guideError != null) loadGuide()
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

    /**
     * A page shows the channels: refresh now when stale (or when the page comes back after a few
     * seconds, e.g. from the player, so the last channel and favourites are current), then every
     * [Channels.REFRESH_MS]. Pages that share this view model (the phone's Home shelf and its «ТВ»
     * tab) each pass their own [watcher] and share the one refresh.
     */
    fun startRefreshing(watcher: Any = this) {
        watchers += watcher
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            var first = true
            while (isActive) {
                val age = clock() - loadedAt
                val wait = if (first && age >= RETURN_REFRESH_MS) 0 else Channels.REFRESH_MS - age
                first = false
                if (wait > 0) delay(wait)
                job?.takeIf { it.isActive }?.join() ?: fetchNow()
            }
        }
    }

    /**
     * The «ТВ» page opened: tells the server (it refreshes the external sources marked «при
     * открытии» when their last refresh is over an hour old), at most every [OPENED_EVERY_MS], and
     * reloads the list afterwards when the server answered. Best effort.
     */
    fun opened() {
        val now = clock()
        openedAt?.let { if (now - it < OPENED_EVERY_MS) return }
        openedAt = now
        viewModelScope.launch {
            try {
                api.opened()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (job?.isActive != true) job = viewModelScope.launch { fetchNow() }
        }
    }

    private var openedAt: Long? = null

    fun stopRefreshing(watcher: Any = this) {
        watchers -= watcher
        if (watchers.isNotEmpty()) return
        refreshJob?.cancel()
        refreshJob = null
    }

    /** (Re)loads the guide from 3 h before now: one page of [Epg.PAGE_HOURS] hours. */
    fun loadGuide() {
        guideJob?.cancel()
        guideError = null
        val from = Epg.origin(clock())
        guideJob = viewModelScope.launch {
            try {
                val page = api.epg(Epg.iso(from), Epg.PAGE_HOURS)
                guideFromMs = from
                guideToMs = from + Epg.PAGE_HOURS * Epg.HOUR_MS
                guideRows = Epg.merge(emptyList(), page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (guideRows == null) guideError = e
            }
        }
    }

    /** The guide scrolled near its loaded end: the next page, up to 47 h ahead. */
    fun loadMoreGuide() {
        if (guideJob?.isActive == true || guideRows == null) return
        val from = guideToMs
        if (from >= Epg.limit(clock())) return
        guideJob = viewModelScope.launch {
            try {
                val page = api.epg(Epg.iso(from), Epg.PAGE_HOURS)
                guideRows = Epg.merge(guideRows.orEmpty(), page)
                guideToMs = from + Epg.PAGE_HOURS * Epg.HOUR_MS
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Scrolling there again retries.
            }
        }
    }

    private suspend fun fetchNow() {
        try {
            val list = api.channels()
            // A channel that did not change stays the same instance, so its row or card is not
            // recomposed by a refresh (an unchanged list is not written at all: the state compares it).
            val known = channels.orEmpty().associateBy { it.id }
            channels = Channels.sortedByNumber(list.channels).map { channel -> known[channel.id]?.takeIf { it == channel } ?: channel }
            lastChannelId = list.lastChannel ?: lastChannelId
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep what is shown; an error replaces only an empty page.
            if (channels == null) error = e
        } finally {
            loadedAt = clock()
        }
    }

    private companion object {
        const val RETURN_REFRESH_MS = 3_000L
        const val OPENED_EVERY_MS = 10 * 60_000L
    }
}
