package app.antresol.core.playback

import app.antresol.core.api.ApiException
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.ProgressUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The server calls a [PlaybackReporter] makes; [AntresolClient.playbackApi] is the real one. */
interface PlaybackApi {
    suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long)
    suspend fun keepalive(session: String)
    suspend fun stopSession(session: String)
}

fun AntresolClient.playbackApi(): PlaybackApi = object : PlaybackApi {
    override suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long) =
        this@playbackApi.saveProgress(itemId, ProgressUpdate.playback(positionMs, durationMs))

    override suspend fun keepalive(session: String) {
        hlsKeepalive(session)
    }

    override suspend fun stopSession(session: String) = hlsStop(session)
}

/** Intervals from the spec: progress every 10 s, HLS keepalive every 20 s. */
data class PlaybackTiming(
    val progressInterval: Duration = 10.seconds,
    val keepaliveInterval: Duration = 20.seconds,
    /** Periodic writes are skipped when the position moved less than this. */
    val minProgressDeltaMs: Long = 500,
) {
    companion object {
        val VIDEO = PlaybackTiming()

        /** Audiobooks skip writes when the position is unchanged at whole-second granularity. */
        val AUDIOBOOK = PlaybackTiming(minProgressDeltaMs = 1_000)
    }
}

/**
 * Reports one playback to the server: periodic progress writes while playing, HLS keepalives while a
 * session is open, a write on pause/background ([flush]), and a final write plus session stop ([stop]).
 *
 * [positionMs] must return the position on the item timeline (the file position for video, the
 * whole-book position for audiobooks) and is called from [scope]; for ExoPlayer use a Main scope.
 */
class PlaybackReporter(
    private val api: PlaybackApi,
    private val scope: CoroutineScope,
    private val itemId: Long,
    private val durationMs: Long,
    private val positionMs: () -> Long,
    private val isPlaying: () -> Boolean,
    private val timing: PlaybackTiming = PlaybackTiming.VIDEO,
    /** Keepalive got 404: the HLS session is gone; re-issue `/api/play` without `replaceSession`. */
    private val onSessionLost: (session: String) -> Unit = {},
    /** Non-fatal errors from background writes (network failures, server errors). */
    private val onError: (Throwable) -> Unit = {},
) {
    private val saveLock = Mutex()
    private var lastSavedMs: Long? = null
    private var progressJob: Job? = null
    private var keepaliveJob: Job? = null
    private var stopped = false

    /** The open HLS session, or null for direct play. */
    var hlsSession: String? = null
        private set

    /** Starts the progress ticker and, when [session] is given, the keepalive ticker. */
    fun start(session: String? = null) {
        check(!stopped) { "PlaybackReporter was stopped" }
        if (progressJob == null) {
            progressJob = scope.launch {
                while (isActive) {
                    delay(timing.progressInterval)
                    if (isPlaying()) save(minDelta = timing.minProgressDeltaMs)
                }
            }
        }
        attachSession(session)
    }

    /**
     * Switches keepalives to a new HLS session, e.g. after `/api/play` with `replaceSession`
     * (the server already stopped the old one) or after a fallback to direct play (null).
     */
    fun attachSession(session: String?) {
        if (session == hlsSession && keepaliveJob?.isActive == true) return
        keepaliveJob?.cancel()
        keepaliveJob = null
        hlsSession = session
        if (session == null) return
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(timing.keepaliveInterval)
                try {
                    api.keepalive(session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.isNotFound) {
                        if (hlsSession == session) hlsSession = null
                        onSessionLost(session)
                        return@launch
                    }
                    onError(e)
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /** Saves the current position if it changed at all. Call on pause and when the app goes to background. */
    suspend fun flush() = save(minDelta = 1)

    /** Final write, then stops the HLS session. Safe to call more than once. */
    suspend fun stop() {
        if (stopped) return
        stopped = true
        progressJob?.cancel()
        keepaliveJob?.cancel()
        progressJob = null
        keepaliveJob = null
        flush()
        val session = hlsSession ?: return
        hlsSession = null
        try {
            api.stopSession(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (!e.isNotFound) onError(e)
        } catch (e: Exception) {
            onError(e)
        }
    }

    private suspend fun save(minDelta: Long) = saveLock.withLock {
        val position = positionMs().coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val last = lastSavedMs
        if (last != null && abs(position - last) < minDelta) return@withLock
        try {
            api.saveProgress(itemId, position, durationMs)
            lastSavedMs = position
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }
}
