package app.antresol.core.playback

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.model.Quality
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/** The server calls a [VideoSession] makes; [AntresolClient.videoApi] is the real one. */
interface VideoApi {
    suspend fun play(fileId: Long, request: PlayRequest): PlayResponse
    /** A media key that stays valid for at least [minValidMs] more. */
    suspend fun mediaKey(minValidMs: Long): String
    suspend fun stopSession(session: String)
    fun playbackUrl(url: String, mediaKey: String): String
}

fun AntresolClient.videoApi(): VideoApi = object : VideoApi {
    override suspend fun play(fileId: Long, request: PlayRequest) = this@videoApi.play(fileId, request)
    override suspend fun mediaKey(minValidMs: Long) = mediaKeys.get(Duration.ofMillis(minValidMs))
    override suspend fun stopSession(session: String) = hlsStop(session)
    override fun playbackUrl(url: String, mediaKey: String) = urls.playback(url, mediaKey)
}

/** One answer of `/api/play`, ready for the player: the absolute `?mk=` URL and the timeline. */
data class VideoSource(val play: PlayResponse, val url: String) {
    val timeline = StreamTimeline(play)
    val session: String? get() = play.hlsSession
    val isHls: Boolean get() = play.isHls
}

/**
 * Opens a video **file** the way the spec describes (section 11): `/api/play` with caps, quality and
 * audio track; the URL gets `?mk=` so the player needs no headers. Reopening (seek before the HLS
 * start, audio track or quality change) passes `replaceSession`, so the profile never holds more
 * than one session for this player. Keepalives and the final stop belong to [PlaybackReporter].
 */
class VideoSession(
    private val api: VideoApi,
    val fileId: Long,
    private val caps: PlaybackCaps,
    quality: String = Quality.AUTO,
    audioIndex: Int? = null,
) {
    var quality: String = quality
        private set

    /** The audio stream index asked for; null lets the server pick the default track. */
    var audioIndex: Int? = audioIndex
        private set

    var source: VideoSource? = null
        private set

    /** The HLS session this player holds on the server, or null (direct play, lost or suspended). */
    var session: String? = null
        private set

    /**
     * Opens the stream at [startMs] (file time), replacing the HLS session this player holds, if any.
     * Throws [ApiException] (429 = the profile already has too many streams).
     */
    suspend fun open(
        startMs: Long,
        quality: String = this.quality,
        audioIndex: Int? = this.audioIndex,
    ): VideoSource {
        val request = PlayRequest(
            caps = caps,
            startSec = (startMs.coerceAtLeast(0) / 1000.0).takeIf { it > 0 },
            quality = quality.takeIf { it != Quality.AUTO },
            audioIndex = audioIndex,
            replaceSession = session,
        )
        val play = api.play(fileId, request)
        // Every segment URI (and a direct stream's range requests) reuse this key until the end.
        val remainingMs = (play.durationMs - startMs).coerceAtLeast(0)
        val opened = VideoSource(play, api.playbackUrl(play.url, api.mediaKey(remainingMs + KEY_MARGIN_MS)))
        this.quality = quality
        this.audioIndex = audioIndex
        source = opened
        session = opened.session
        return opened
    }

    /** Forgets the HLS session and returns it, so the caller can stop it later. */
    fun detachSession(): String? = session.also { session = null }

    /** Forgets the HLS session without stopping it (the server already dropped it). */
    fun sessionLost() {
        session = null
    }

    /** Stops the HLS session now (the app went to background); [open] starts a new one later. */
    suspend fun suspendSession() {
        stopSession(detachSession() ?: return)
    }

    /** Stops [id] (a session from [detachSession], or one opened by a cancelled request), best effort. */
    suspend fun stopSession(id: String) {
        try {
            api.stopSession(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: an unreachable server reaps the session on its own.
        }
    }

    private companion object {
        const val KEY_MARGIN_MS = 60 * 60 * 1000L
    }
}
