package app.antresol.core.ways

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.InputPlay
import app.antresol.core.model.InputProgressBody
import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.RunRequest
import app.antresol.core.model.WayPlay
import app.antresol.core.model.WayProgressBody
import app.antresol.core.playback.PlaybackApi
import app.antresol.core.playback.VideoApi
import kotlinx.coroutines.delay
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** What the player shows about a way's video while it opens (the server's own player screens have an item for this). */
data class PlaybackDisplay(
    val title: String,
    val kind: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val season: Int? = null,
    val episode: Int? = null,
)

/** What a run asked a player to play. */
sealed interface WayPlayback {
    val display: PlaybackDisplay

    /** The run that made it: the player offers its choice again («Audio & source…») from this. */
    val call: WayCall?

    /** Through the core's input player (`/api/play/input/{id}`). */
    data class Input(val id: String, override val display: PlaybackDisplay, override val call: WayCall? = null) : WayPlayback

    /**
     * The addon's own stream. [request] is the run that made it: it is sent again with `refresh`
     * when the media link goes stale (410).
     */
    data class Direct(
        val addonId: String,
        val request: RunRequest,
        val play: WayPlay,
        override val display: PlaybackDisplay,
        override val call: WayCall? = null,
    ) : WayPlayback
}

/**
 * Hands a [WayPlayback] to the player screen that opens next (an activity's intent, a route) as a
 * short token: the playback itself is not text. The few last stay; after the process was killed a
 * token finds nothing and the player just closes.
 */
object WayTicket {
    private const val KEEP = 8
    private val counter = AtomicLong()
    private val entries = LinkedHashMap<String, WayPlayback>()

    fun put(playback: WayPlayback): String = synchronized(entries) {
        val token = "way-${counter.incrementAndGet()}"
        entries[token] = playback
        while (entries.size > KEEP) entries.remove(entries.keys.first())
        token
    }

    fun get(token: String): WayPlayback? = synchronized(entries) { entries[token] }
}

/** The video stayed «not ready» (the server kept answering 202) for longer than [InputPlayback.WAIT_BUDGET_MS]. */
class InputNotReadyException : Exception("The input was not ready in time")

/** Asking the server how to play an input (`POST /api/play/input/{id}`), waiting while it is prepared. */
object InputPlayback {
    /** How long a video that is still being prepared (202) is waited for. */
    const val WAIT_BUDGET_MS = 90_000L

    /** Between two asks. */
    const val STEP_MS = 1_500L

    /**
     * The answer for [request], asking again every [STEP_MS] while the server says it is not ready, up
     * to [budgetMs] of waiting; then [InputNotReadyException]. A failure of [ask] is thrown as is.
     */
    suspend fun open(
        budgetMs: Long = WAIT_BUDGET_MS,
        wait: suspend (Long) -> Unit = { delay(it) },
        ask: suspend () -> InputPlay,
    ): PlayResponse {
        var waited = 0L
        while (true) {
            when (val answer = ask()) {
                is InputPlay.Ready -> return answer.play
                InputPlay.Pending -> {
                    if (waited >= budgetMs) throw InputNotReadyException()
                    val step = STEP_MS.coerceAtMost(budgetMs - waited).coerceAtLeast(1)
                    wait(step)
                    waited += step
                }
            }
        }
    }
}

/** The input [id] as a video the player opens like a file: `/api/play/input/{id}` with the player's caps. */
fun AntresolClient.inputVideoApi(id: String): VideoApi = object : VideoApi {
    private val client = this@inputVideoApi

    override suspend fun play(fileId: Long, request: PlayRequest): PlayResponse =
        InputPlayback.open { client.inputPlay(id, request) }

    override suspend fun mediaKey(minValidMs: Long) = client.mediaKeys.get(Duration.ofMillis(minValidMs))
    override suspend fun stopSession(session: String) = client.hlsStop(session)
    override fun playbackUrl(url: String, mediaKey: String) = client.urls.playback(url, mediaKey)
}

/** Progress of the input [id] (the library item it names, and the addon), and the keepalive of its HLS session. */
fun AntresolClient.inputPlaybackApi(id: String): PlaybackApi {
    val client = pinned()
    return object : PlaybackApi {
        override suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long) = write(positionMs, durationMs, keepalive = false)

        override suspend fun saveFinalProgress(itemId: Long, positionMs: Long, durationMs: Long) = write(positionMs, durationMs, keepalive = true)

        override suspend fun keepalive(session: String) {
            client.hlsKeepalive(session)
        }

        override suspend fun stopSession(session: String) = client.hlsStop(session)

        // The server refuses a position past the duration (plus a few seconds); a player's duration is an estimate.
        private suspend fun write(positionMs: Long, durationMs: Long, keepalive: Boolean) =
            client.inputProgress(id, InputProgressBody(positionMs, max(durationMs, positionMs), keepalive))
    }
}

/**
 * Progress of a stream an addon serves itself (`PUT x/app/progress`), under the addon's [session];
 * stopping it closes that session. There is no keepalive: the addon's streams have none. [ended]
 * says the video played to its end (the write then carries `finished`).
 */
fun AntresolClient.wayPlaybackApi(addonId: String, request: RunRequest, session: String, ended: () -> Boolean): PlaybackApi {
    val client = pinned()
    return object : PlaybackApi {
        override suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long) {
            val finished = ended() || (durationMs > 0 && positionMs >= durationMs)
            client.wayProgress(
                addonId,
                WayProgressBody(
                    session = session,
                    way = request.way,
                    kind = request.kind,
                    tmdbId = request.tmdbId,
                    season = request.season,
                    episode = request.episode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    finished = finished.takeIf { it },
                ),
            )
        }

        override suspend fun keepalive(session: String) = Unit

        override suspend fun stopSession(session: String) = client.wayStop(addonId, session)
    }
}
