package app.antresol.core.channels

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.attempt
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelCategory
import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.Quality
import app.antresol.core.model.QualityInfo
import app.antresol.core.model.StreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every server call the live player may make; [AntresolClient.liveApi] is the real one. TV is TV:
 * the only writes are [setLast] and [setFavorite]; nothing here touches progress, «Continue
 * Watching», watched flags, track memory or scrobbles (see LiveTvNoHistoryTest). An external
 * channel's video comes from its provider, not from these calls (only [externalUrl] may ask the
 * server for a media key).
 */
interface LiveApi {
    suspend fun channels(): List<Channel>

    /** The server's categories of the list [channels] last read (to name the category a player was opened from); null: none. */
    suspend fun categories(): List<ChannelCategory>? = null

    /** Whether the list [channels] last read has the favourites first ([app.antresol.core.model.ChannelList.favoritesFirst]). */
    suspend fun favoritesFirst(): Boolean = false

    suspend fun guide(channelId: Long): List<ChannelProgram>

    /** Starts the channel's stream and returns the URL for the player (see [AntresolClient.openLiveChannel]). */
    suspend fun open(channelId: Long): String

    /** [open] in the [app.antresol.core.model.Quality] [quality] (`?q=` on the master; auto: none). */
    suspend fun open(channelId: Long, quality: String): String = open(channelId)

    /** What the server applies to our channels for the choice [quality] (`/api/channels/live/quality`); null: unknown. */
    suspend fun quality(quality: String): QualityInfo? = null

    /** `PUT /api/channels/last`: the channel tuned in becomes the profile's last channel. */
    suspend fun setLast(channelId: Long)

    suspend fun setFavorite(channelId: Long, favorite: Boolean)

    /** The profile's languages, for the default audio and subtitles (read only). */
    suspend fun prefs(): PlaybackPrefs

    /**
     * The URL to hand the player for an external channel's stream [url] (see
     * [AntresolClient.externalStreamUrl]): provider URLs as they are, the server's hidden-address
     * form signed with the media key.
     */
    suspend fun externalUrl(url: String): String = url
}

fun AntresolClient.liveApi(): LiveApi {
    val client = this
    return object : LiveApi {
        private var categories: List<ChannelCategory>? = null
        private var favoritesFirst = false

        override suspend fun channels(): List<Channel> = client.channels().also {
            categories = it.categories
            favoritesFirst = it.favoritesFirst
        }.channels

        override suspend fun categories(): List<ChannelCategory>? = categories

        override suspend fun favoritesFirst(): Boolean = favoritesFirst

        override suspend fun guide(channelId: Long): List<ChannelProgram> = client.channelGuide(channelId, Channels.GUIDE_HOURS).programs

        override suspend fun open(channelId: Long): String = client.openLiveChannel(channelId)

        override suspend fun open(channelId: Long, quality: String): String = client.openLiveChannel(channelId, quality)

        override suspend fun quality(quality: String): QualityInfo = client.liveQuality(quality)

        override suspend fun setLast(channelId: Long) = client.setLastChannel(channelId)

        override suspend fun setFavorite(channelId: Long, favorite: Boolean) = client.setChannelFavorite(channelId, favorite)

        override suspend fun prefs(): PlaybackPrefs = client.playbackPrefs()

        override suspend fun externalUrl(url: String): String = client.externalStreamUrl(url)
    }
}

/** The stream of the channel being watched. */
sealed interface LiveStream {
    /** Opening the stream; [attempt] counts the automatic retries (0 for the first try). */
    data class Starting(val attempt: Int) : LiveStream

    /**
     * Hand [url] to the player. [generation] changes on every reopen, so the same URL is loaded
     * again. An [external] channel's stream comes from its provider with the [userAgent] and
     * [referrer] its playlist asked for; [source] is its index among the stream and its fallbacks.
     */
    data class Ready(
        val url: String,
        val generation: Int,
        val external: Boolean = false,
        val userAgent: String? = null,
        val referrer: String? = null,
        val source: Int = 0,
        /** An external stream's [app.antresol.core.model.StreamSource.format]: `hls`, `ts` or null (unknown). */
        val format: String? = null,
        /** How many sources the external channel has (the banner's «Источник n из m»). */
        val sourceCount: Int = 1,
        /** The external source's own URL is the server's hidden-address redirect («Через Antresol»). */
        val viaServer: Boolean = false,
    ) : LiveStream

    /**
     * The channel can't be shown: the server's [message], or null for a generic one. [external]:
     * an external channel none of whose streams played («Канал сейчас не открывается»); nothing
     * retries it by itself.
     */
    data class Failed(val message: String?, val external: Boolean = false) : LiveStream
}

/** What the live player shows: the channels to switch between, the current one and its guide. */
data class LiveTvState(
    /** Every channel in the list's order ([Channels.ordered]; empty until loaded). */
    val channels: List<Channel>,
    val channelId: Long,
    /** The guide of [channelId] from the programme on air on (empty until loaded). */
    val programs: List<ChannelProgram>,
    val stream: LiveStream,
    /** The list the player was opened from: zapping and the mini-guide stay inside it. */
    val filter: ChannelFilter = ChannelFilter.All,
    /** The profile's languages, null until loaded (or when they could not be read). */
    val prefs: PlaybackPrefs? = null,
    /** The server's categories (names for a [filter] that is one), empty until loaded or without them. */
    val categories: List<ChannelCategory> = emptyList(),
    /** The list has the favourites first (the server's order; a star changed here moves its channel). */
    val favoritesFirst: Boolean = false,
    /** The channel watched before this one («Предыдущий канал»), null until a switch. */
    val previousChannelId: Long? = null,
    /** Every source of the external channel on screen plays without a sound the device decodes. */
    val noSupportedAudio: Boolean = false,
) {
    val channel: Channel? get() = channels.firstOrNull { it.id == channelId }

    /** The channels of [filter], in the list's order. */
    val listed: List<Channel> get() = Channels.filter(channels, filter, categories)

    /**
     * The programme on air at [nowMs] and the one after it: from the guide once loaded, else from
     * the channel list's now/next.
     */
    fun onAir(nowMs: Long): Pair<ChannelProgram?, ChannelProgram?> {
        val (guideNow, guideNext) = Channels.onAir(programs, nowMs)
        if (guideNow != null) return guideNow to guideNext
        val channel = channel ?: return null to null
        return Channels.showing(channel, nowMs) to channel.next?.takeIf { it != Channels.showing(channel, nowMs) }
    }
}

/**
 * The mini-guide of [state] at [nowMs]: the channel on screen shows what its guide says is on now
 * and next (the channel list is up to 30 s older), the others what the list says.
 */
fun liveMiniGuide(state: LiveTvState, nowMs: Long): MiniGuide {
    val guide = Channels.miniGuide(state.listed, state.channelId)
    val (now, next) = Channels.onAir(state.programs, nowMs)
    if (now == null) return guide
    return guide.copy(channels = guide.channels.map { if (it.id == state.channelId) it.copy(now = now, next = next) else it })
}

/**
 * The live player's logic without the player: opens the channel's stream with the retries of
 * [LiveStartPolicy] (the encoder may need a few seconds), switches channels by number ([zap], wrapping),
 * and reloads the channel list and the guide every [Channels.REFRESH_MS] while [start]ed. The
 * player reports back with [streamPlaying] and [streamFailed]; a failed stream is opened again, and
 * that request tells the real reason (starting, busy, screen time over).
 *
 * An external channel is played straight from its provider: its stream, then its fallbacks in
 * order (skipping the ones marked unsupported on [platform]); a source that fails before playing
 * hands over to the next one at once, a source that broke while playing is reopened once or twice.
 * When none plays the stream is [LiveStream.Failed] with `external` and stays so until the viewer
 * retries or switches channel — no endless retrying of a dead provider.
 */
class LiveTvSession(
    private val api: LiveApi,
    private val scope: CoroutineScope,
    channelId: Long,
    filter: ChannelFilter = ChannelFilter.All,
    private val platform: String = PLATFORM_ANDROID,
    quality: String = Quality.AUTO,
    /** The external sources that played badly here: tried last. */
    private val sourceMemory: ExternalSourceMemory = InMemoryExternalSourceMemory(),
) {
    /** The [Quality] our channels are opened in (`?q=`); external channels play their own variants. */
    var quality: String = quality
        private set

    private val _state = MutableStateFlow(LiveTvState(emptyList(), channelId, emptyList(), LiveStream.Starting(0), filter))
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private var failures = 0
    private var generation = 0
    private var openJob: Job? = null
    private var guideJob: Job? = null
    private var refreshJob: Job? = null
    private var lastMarked: Long? = null

    /** The external channel being played: its playable sources, the one on, and its history. */
    private var externalSources: List<StreamSource> = emptyList()
    private var externalIndex = 0
    private var externalPlayed = false
    private var externalReopens = 0
    /** The sources of this opening that played without sound, and the one kept although silent. */
    private var silentSources = mutableSetOf<Int>()
    private var acceptedSilent = false

    /** Opens the stream and starts the periodic refresh (again after [stop]). */
    fun start() {
        if (refreshJob?.isActive == true) return
        failures = 0
        open()
        refreshJob = scope.launch {
            if (_state.value.prefs == null) launch { loadPrefs() }
            loadChannels()
            while (isActive) {
                delay(Channels.REFRESH_MS)
                loadChannels()
                loadGuide(_state.value.channelId)
            }
        }
        reloadGuide()
    }

    /** Stops opening streams and refreshing (the app went to background, the player closed). */
    fun stop() {
        openJob?.cancel()
        guideJob?.cancel()
        refreshJob?.cancel()
        openJob = null
        guideJob = null
        refreshJob = null
    }

    /**
     * Switches to the channel [step] places away in the list the player was opened from (+1 up, −1
     * down), by number, wrapping. False when there is none.
     */
    /** The list the player zaps in becomes [filter] (the TV player's category column); the channel on stays. */
    fun setFilter(filter: ChannelFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun zap(step: Int): Boolean {
        val state = _state.value
        val target = Channels.neighbour(state.listed, state.channelId, step, state.channel?.number) ?: return false
        return tune(target.id)
    }

    /**
     * Makes [channelId] a favourite of the profile, or not: shown at once, sent to the server, and
     * undone when the server refuses.
     */
    fun setFavorite(channelId: Long, favorite: Boolean) {
        setFavoriteLocally(channelId, favorite)
        scope.launch {
            val ok = attempt { api.setFavorite(channelId, favorite) } != null
            if (!ok) setFavoriteLocally(channelId, !favorite)
        }
    }

    private fun setFavoriteLocally(channelId: Long, favorite: Boolean) {
        _state.update { state ->
            val channels = state.channels.map { if (it.id == channelId) it.copy(favorite = favorite) else it }
            state.copy(channels = if (state.favoritesFirst) Channels.ordered(channels, true) else channels)
        }
    }

    /** Switches to [channelId] (the mini-guide). False when it is already on. */
    fun tune(channelId: Long): Boolean {
        val current = _state.value.channelId
        if (channelId == current) return false
        _state.update { it.copy(channelId = channelId, programs = emptyList(), previousChannelId = current, noSupportedAudio = false) }
        failures = 0
        open()
        reloadGuide()
        return true
    }

    /**
     * The viewer picks a quality: our channel's stream is opened again in it (at the live edge); an
     * external channel is not reopened (the player limits its own variants).
     */
    fun setQuality(value: String) {
        if (value == quality) return
        quality = value
        val state = _state.value
        if (state.channel?.external == true || (state.stream as? LiveStream.Ready)?.external == true) return
        failures = 0
        open()
    }

    /** «Предыдущий канал»: back to the channel watched before (and from there back again). False when there is none. */
    fun tunePrevious(): Boolean {
        val previous = _state.value.previousChannelId ?: return false
        return tune(previous)
    }

    /**
     * The external stream on screen shows pictures but has no sound the device decodes (no audio
     * track, or none it supports): the next source, remembered as bad; with none left it plays on
     * with [LiveTvState.noSupportedAudio].
     */
    fun streamSilent() {
        val stream = _state.value.stream as? LiveStream.Ready ?: return
        if (!stream.external) return
        if (acceptedSilent) {
            _state.update { it.copy(noSupportedAudio = true) }
            return
        }
        silentSources += externalIndex
        externalSources.getOrNull(externalIndex)?.let { sourceMemory.markBad(_state.value.channelId, it.url) }
        when (val step = ExternalFallback.afterSilence(externalIndex, externalSources.size)) {
            is ExternalFallback.Step.TryNext -> switchExternal(step.index)
            is ExternalFallback.Step.AcceptSilent -> {
                acceptedSilent = true
                _state.update { it.copy(noSupportedAudio = true) }
            }
            ExternalFallback.Step.GiveUp -> Unit
        }
    }

    /** The own URL of the external source on screen (a path for the server's redirect); null for our channels. */
    fun currentSourceUrl(): String? =
        if ((_state.value.stream as? LiveStream.Ready)?.external == true) externalSources.getOrNull(externalIndex)?.url else null

    /** The external stream on screen plays with sound: its source loses a bad mark. */
    fun streamHasSound() {
        val stream = _state.value.stream as? LiveStream.Ready ?: return
        if (!stream.external) return
        externalSources.getOrNull(externalIndex)?.let { sourceMemory.markGood(_state.value.channelId, it.url) }
    }

    /**
     * The stream has waited for data for a long time while playing: an external channel moves to
     * its next source (this one remembered as bad); the last source, or our channel, is opened again
     * at the live edge.
     */
    fun streamStalled() {
        val stream = _state.value.stream as? LiveStream.Ready ?: return
        if (stream.external && !acceptedSilent && externalIndex + 1 < externalSources.size) {
            externalSources.getOrNull(externalIndex)?.let { sourceMemory.markBad(_state.value.channelId, it.url) }
            switchExternal(externalIndex + 1)
        } else {
            streamFailed()
        }
    }

    private fun switchExternal(index: Int) {
        val channelId = _state.value.channelId
        externalIndex = index
        externalPlayed = false
        externalReopens = 0
        openJob?.cancel()
        _state.update { it.copy(stream = LiveStream.Starting(externalIndex)) }
        openJob = scope.launch { openExternal(channelId) }
    }

    /** "Try Again" after a failure. */
    fun retry() {
        failures = 0
        open()
    }

    /** The player shows pictures: later failures get the whole retry budget again. */
    fun streamPlaying() {
        failures = 0
        if ((_state.value.stream as? LiveStream.Ready)?.external == true) externalPlayed = true
    }

    /** The player could not load or play the stream: open it again (the request finds out why). */
    fun streamFailed() {
        val stream = _state.value.stream as? LiveStream.Ready ?: return
        if (stream.external) {
            externalFailed()
            return
        }
        failures++
        // The server answers but the player keeps failing (a decoder problem): give up at some point.
        if (failures >= LiveStartPolicy.MAX_ATTEMPTS) {
            _state.update { it.copy(stream = LiveStream.Failed(null)) }
            return
        }
        open(delayMs = RETRY_AFTER_PLAYER_ERROR_MS)
    }

    private fun open(delayMs: Long = 0) {
        openJob?.cancel()
        val channelId = _state.value.channelId
        _state.update { it.copy(stream = LiveStream.Starting(failures), noSupportedAudio = false) }
        openJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            // Whether the channel is external is in the list: wait for its first load (briefly).
            if (_state.value.channels.isEmpty()) {
                withTimeoutOrNull(CHANNELS_WAIT_MS) { _state.first { it.channels.isNotEmpty() } }
            }
            val channel = _state.value.channels.firstOrNull { it.id == channelId }
            if (channel?.external == true) {
                // the sources that played badly here lately go last
                externalSources = sourceMemory.ordered(channelId, channel.stream?.sources.orEmpty().filter(::playable))
                externalIndex = 0
                externalPlayed = false
                externalReopens = 0
                silentSources = mutableSetOf()
                acceptedSilent = false
                openExternal(channelId)
                return@launch
            }
            while (isActive) {
                try {
                    val url = api.open(channelId, quality)
                    _state.update { it.copy(stream = LiveStream.Ready(url, ++generation)) }
                    markLast(channelId)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    when (val decision = LiveStartPolicy.decide(e, failures)) {
                        is LiveRetry.After -> {
                            _state.update { it.copy(stream = LiveStream.Starting(failures)) }
                            delay(decision.delayMs)
                        }
                        is LiveRetry.Fail -> {
                            _state.update { it.copy(stream = LiveStream.Failed(decision.message)) }
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private fun playable(source: StreamSource): Boolean =
        source.url.isNotBlank() && source.unsupported.none { it == platform || it == PLATFORM_ANDROID }

    /**
     * Hands the player the external source [externalIndex] (skipping the ones whose URL cannot be
     * made), or gives up when there is none left.
     */
    private suspend fun openExternal(channelId: Long) {
        while (externalIndex < externalSources.size) {
            val source = externalSources[externalIndex]
            val url = attempt { api.externalUrl(source.url) }
            if (url != null) {
                _state.update {
                    if (it.channelId != channelId) it
                    else it.copy(
                        stream = LiveStream.Ready(
                            url, ++generation, true, source.userAgent, source.referrer, externalIndex, source.format,
                            sourceCount = externalSources.size, viaServer = source.url.startsWith("/"),
                        ),
                    )
                }
                markLast(channelId)
                return
            }
            externalIndex++
        }
        _state.update { if (it.channelId != channelId) it else it.copy(stream = LiveStream.Failed(null, external = true)) }
    }

    /**
     * The player could not play the external source on: a source that played is reopened (a blip
     * of the provider, at most [MAX_EXTERNAL_REOPENS] times), one that never played hands over to
     * the next fallback at once.
     */
    private fun externalFailed() {
        val channelId = _state.value.channelId
        val reopen = externalPlayed && externalReopens < MAX_EXTERNAL_REOPENS
        externalPlayed = false
        if (reopen) {
            externalReopens++
        } else {
            externalSources.getOrNull(externalIndex)?.let { sourceMemory.markBad(channelId, it.url) }
            // the silent source kept as the last resort failed too: nothing more to try
            val step = if (acceptedSilent) ExternalFallback.Step.GiveUp else ExternalFallback.afterFailure(externalIndex, externalSources.size, silentSources)
            when (step) {
                is ExternalFallback.Step.TryNext -> externalIndex = step.index
                is ExternalFallback.Step.AcceptSilent -> {
                    externalIndex = step.index
                    acceptedSilent = true
                }
                // past the end: openExternal fails the channel
                ExternalFallback.Step.GiveUp -> externalIndex = externalSources.size
            }
        }
        openJob?.cancel()
        _state.update { it.copy(stream = LiveStream.Starting(externalIndex)) }
        openJob = scope.launch {
            if (reopen) delay(RETRY_AFTER_PLAYER_ERROR_MS)
            openExternal(channelId)
        }
    }

    /** The channel is tuned in: it becomes the profile's last channel (once per switch, best effort). */
    private fun markLast(channelId: Long) {
        if (lastMarked == channelId) return
        lastMarked = channelId
        scope.launch {
            if (attempt { api.setLast(channelId) } == null && lastMarked == channelId) lastMarked = null
        }
    }

    private suspend fun loadPrefs() {
        val prefs = attempt { api.prefs() } ?: return
        _state.update { it.copy(prefs = prefs) }
    }

    private fun reloadGuide() {
        guideJob?.cancel()
        val channelId = _state.value.channelId
        guideJob = scope.launch { loadGuide(channelId) }
    }

    private suspend fun loadChannels() {
        val channels = attempt { api.channels() } ?: return
        val categories = api.categories().orEmpty()
        val favoritesFirst = api.favoritesFirst()
        // The server's order as it is (by number, the favourites first when the profile wants them so).
        _state.update { it.copy(channels = channels, categories = categories, favoritesFirst = favoritesFirst) }
    }

    private suspend fun loadGuide(channelId: Long) {
        val programs = attempt { api.guide(channelId) } ?: return
        _state.update { if (it.channelId == channelId) it.copy(programs = programs) else it }
    }

    companion object {
        /** A stream that broke while playing is reopened after this pause. */
        const val RETRY_AFTER_PLAYER_ERROR_MS = 1_000L

        /** How long opening a channel waits for the first channel list (is it external?). */
        const val CHANNELS_WAIT_MS = 10_000L

        /** An external stream that broke while playing is reopened this many times before the next fallback. */
        const val MAX_EXTERNAL_REOPENS = 2

        /** The `unsupported` platform names of the Android apps (phone; the TV adds its own). */
        const val PLATFORM_ANDROID = "android"
        const val PLATFORM_ANDROID_TV = "androidtv"
    }
}
