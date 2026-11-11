package app.antresol.core.channels

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The server calls of the live player; [AntresolClient.liveApi] is the real one. */
interface LiveApi {
    suspend fun channels(): List<Channel>

    suspend fun guide(channelId: Long): List<ChannelProgram>

    /** Starts the channel's stream and returns the URL for the player (see [AntresolClient.openLiveChannel]). */
    suspend fun open(channelId: Long): String
}

fun AntresolClient.liveApi(): LiveApi {
    val client = this
    return object : LiveApi {
        override suspend fun channels(): List<Channel> = client.channels().channels

        override suspend fun guide(channelId: Long): List<ChannelProgram> = client.channelGuide(channelId, Channels.GUIDE_HOURS).programs

        override suspend fun open(channelId: Long): String = client.openLiveChannel(channelId)
    }
}

/** The stream of the channel being watched. */
sealed interface LiveStream {
    /** Opening the stream; [attempt] counts the automatic retries (0 for the first try). */
    data class Starting(val attempt: Int) : LiveStream

    /** Hand [url] to the player. [generation] changes on every reopen, so the same URL is loaded again. */
    data class Ready(val url: String, val generation: Int) : LiveStream

    /** The channel can't be shown: the server's [message], or null for a generic one. */
    data class Failed(val message: String?) : LiveStream
}

/** What the live player shows: the channels to switch between, the current one and its guide. */
data class LiveTvState(
    /** Every channel, by number (empty until loaded). */
    val channels: List<Channel>,
    val channelId: Long,
    /** The guide of [channelId] from the programme on air on (empty until loaded). */
    val programs: List<ChannelProgram>,
    val stream: LiveStream,
) {
    val channel: Channel? get() = channels.firstOrNull { it.id == channelId }

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
    val guide = Channels.miniGuide(state.channels, state.channelId)
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
 */
class LiveTvSession(
    private val api: LiveApi,
    private val scope: CoroutineScope,
    channelId: Long,
) {
    private val _state = MutableStateFlow(LiveTvState(emptyList(), channelId, emptyList(), LiveStream.Starting(0)))
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private var failures = 0
    private var generation = 0
    private var openJob: Job? = null
    private var guideJob: Job? = null
    private var refreshJob: Job? = null

    /** Opens the stream and starts the periodic refresh (again after [stop]). */
    fun start() {
        if (refreshJob?.isActive == true) return
        failures = 0
        open()
        refreshJob = scope.launch {
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

    /** Switches to the channel [step] numbers away (+1 up, −1 down), wrapping. False when there is none. */
    fun zap(step: Int): Boolean {
        val target = Channels.neighbour(_state.value.channels, _state.value.channelId, step) ?: return false
        return tune(target.id)
    }

    /** Switches to [channelId] (the mini-guide). False when it is already on. */
    fun tune(channelId: Long): Boolean {
        if (channelId == _state.value.channelId) return false
        _state.update { it.copy(channelId = channelId, programs = emptyList()) }
        failures = 0
        open()
        reloadGuide()
        return true
    }

    /** "Try Again" after a failure. */
    fun retry() {
        failures = 0
        open()
    }

    /** The player shows pictures: later failures get the whole retry budget again. */
    fun streamPlaying() {
        failures = 0
    }

    /** The player could not load or play the stream: open it again (the request finds out why). */
    fun streamFailed() {
        if (_state.value.stream !is LiveStream.Ready) return
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
        _state.update { it.copy(stream = LiveStream.Starting(failures)) }
        openJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            while (isActive) {
                try {
                    val url = api.open(channelId)
                    _state.update { it.copy(stream = LiveStream.Ready(url, ++generation)) }
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

    private fun reloadGuide() {
        guideJob?.cancel()
        val channelId = _state.value.channelId
        guideJob = scope.launch { loadGuide(channelId) }
    }

    private suspend fun loadChannels() {
        val channels = runCatchingNonCancel { api.channels() } ?: return
        _state.update { it.copy(channels = Channels.sortedByNumber(channels)) }
    }

    private suspend fun loadGuide(channelId: Long) {
        val programs = runCatchingNonCancel { api.guide(channelId) } ?: return
        _state.update { if (it.channelId == channelId) it.copy(programs = programs) else it }
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** A stream that broke while playing is reopened after this pause. */
        const val RETRY_AFTER_PLAYER_ERROR_MS = 1_000L
    }
}
