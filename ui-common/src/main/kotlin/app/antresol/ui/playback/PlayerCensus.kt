package app.antresol.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * How many of the app's players and decoders are alive, for the diagnostics log: a TV box that
 * froze leaves only what was written before. Every player the builders make is counted from its
 * creation to its release, and its decoders from their start to their release (or the player's).
 * Two video decoders at once, a player that is never released, and playback errors are written to
 * [log] when one is set (the TV app sets it; the counts are kept either way).
 */
object PlayerCensus {
    /** Where events go (null: nowhere). Called on the player's application thread (main). */
    @Volatile
    var log: ((String) -> Unit)? = null

    private val lock = Any()
    private var players = 0
    private var videoDecoders = 0
    private var audioDecoders = 0

    /** «players=1 video=1 audio=1»: what is alive now. */
    fun summary(): String = synchronized(lock) { "players=$players video=$videoDecoders audio=$audioDecoders" }

    val livePlayers: Int get() = synchronized(lock) { players }
    val liveVideoDecoders: Int get() = synchronized(lock) { videoDecoders }

    /** Counts [player] (named [kind] in the log) until it is released. */
    @OptIn(UnstableApi::class)
    fun attach(player: ExoPlayer, kind: String) {
        val alive = synchronized(lock) { ++players }
        if (alive > 1) log?.invoke("player+ $kind: $alive players alive (${summary()})")
        player.addAnalyticsListener(Tracker(kind))
    }

    /** One player's decoders, so a release that skips the decoders' own callbacks still balances. */
    @OptIn(UnstableApi::class)
    private class Tracker(private val kind: String) : AnalyticsListener {
        private var video = 0
        private var audio = 0
        private var released = false

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (released) return
            video++
            val total = synchronized(lock) { ++videoDecoders }
            if (total > 1) log?.invoke("decoder+ $kind $decoderName: $total video decoders at once (${summary()})")
        }

        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            if (released || video == 0) return
            video--
            synchronized(lock) { videoDecoders-- }
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (released) return
            audio++
            synchronized(lock) { audioDecoders++ }
        }

        override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            if (released || audio == 0) return
            audio--
            synchronized(lock) { audioDecoders-- }
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            log?.invoke("error $kind ${error.errorCodeName}: ${error.cause?.javaClass?.simpleName.orEmpty()} ${error.cause?.message.orEmpty()}".trim().take(MAX_ERROR_CHARS))
        }

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
            if (released) return
            released = true
            synchronized(lock) {
                players--
                videoDecoders -= video
                audioDecoders -= audio
            }
            video = 0
            audio = 0
        }
    }

    private const val MAX_ERROR_CHARS = 200
}
