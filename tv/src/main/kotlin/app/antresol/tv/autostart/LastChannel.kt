package app.antresol.tv.autostart

import android.content.Context
import app.antresol.core.model.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The live channel that was on screen when the viewer last watched TV, kept on the device for «Где остановился»: after
 * a power cut the server is not there yet when the app starts, and its «last channel» says what was tuned last, not
 * whether the viewer was still watching it. Who it belongs to ([serverId], [userId], [historyId]: the device's
 * separate history at the time, null for the profile's own) keeps it from playing for another profile.
 */
data class LastChannel(val serverId: String, val userId: Long, val historyId: Long?, val channelId: Long)

/** Where [LastChannel] lives; null means the viewer's last screen was not a live channel. */
interface LastChannelStore {
    fun get(): LastChannel?

    fun set(value: LastChannel?)
}

/** [LastChannelStore] in SharedPreferences (read at a cold start, before any server answers). */
class PrefsLastChannelStore(context: Context) : LastChannelStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(): LastChannel? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val channel = prefs.getLong(KEY_CHANNEL, 0).takeIf { it > 0 } ?: return null
        return LastChannel(
            serverId = server,
            userId = prefs.getLong(KEY_USER, 0),
            historyId = prefs.getLong(KEY_HISTORY, 0).takeIf { it > 0 },
            channelId = channel,
        )
    }

    override fun set(value: LastChannel?) {
        val edit = prefs.edit().clear()
        if (value != null) {
            edit.putString(KEY_SERVER, value.serverId)
                .putLong(KEY_USER, value.userId)
                .putLong(KEY_HISTORY, value.historyId ?: 0)
                .putLong(KEY_CHANNEL, value.channelId)
        }
        // Written at once: the TV may lose power right after the viewer tuned in.
        edit.commit()
    }

    private companion object {
        const val FILE = "last_channel"
        const val KEY_SERVER = "server"
        const val KEY_USER = "user"
        const val KEY_HISTORY = "history"
        const val KEY_CHANNEL = "channel"
    }
}

/** What an autostart shows once it knows the channels. */
sealed interface ChannelStart {
    /** The channel plays full screen (the TV tab under it). */
    data class Play(val channelId: Long) : ChannelStart

    /** The TV tab: the channel is gone, or the server did not answer in time. */
    data object OpenTv : ChannelStart

    /** Nothing: the app stays where it was ([AutostartTarget.Last] without a channel to go back to). */
    data object Stay : ChannelStart
}

/** The decisions of an autostart into a live channel, apart from Android and the UI so they can be tested. */
object ChannelAutostart {
    /** How long the app waits for the server after the TV started: its network often comes a few seconds later. */
    const val SERVER_WAIT_MS = 20_000L

    /** Between two tries while it waits. */
    const val RETRY_STEP_MS = 1_500L

    /**
     * [LastChannel] [record] when it belongs to the profile signed in now: the same server and profile, and the same
     * separate history when the profile's [historyId] is known yet (it comes with `/api/me`; offline it is not).
     */
    fun recordFor(record: LastChannel?, serverId: String, userId: Long, historyId: Long?, historyKnown: Boolean): LastChannel? =
        record?.takeIf { it.serverId == serverId && it.userId == userId && (!historyKnown || it.historyId == historyId) }

    /**
     * [AutostartTarget.Last]: the viewer's last screen was the channel of [record] (null: it was not a channel, the
     * app stays as it was). [channels] waits for the server ([untilAnswered]); the channel plays when the list still
     * has it, else the TV tab opens.
     */
    suspend fun resumeLast(record: LastChannel?, channels: suspend () -> List<Channel>?): ChannelStart {
        record ?: return ChannelStart.Stay
        val list = channels() ?: return ChannelStart.OpenTv
        return if (list.any { it.id == record.channelId }) ChannelStart.Play(record.channelId) else ChannelStart.OpenTv
    }

    /**
     * [AutostartTarget.Channel]: the profile's last channel, as the device remembers it ([record]) or else as the
     * server does ([serverLast]); else the first channel. The TV tab opens when there is none or no answer came.
     */
    suspend fun lastChannel(record: LastChannel?, channels: suspend () -> Pair<List<Channel>, Long?>?): ChannelStart {
        val (list, serverLast) = channels() ?: return ChannelStart.OpenTv
        val id = listOfNotNull(record?.channelId, serverLast).firstOrNull { id -> list.any { it.id == id } }
            ?: list.firstOrNull()?.id
        return id?.let { ChannelStart.Play(it) } ?: ChannelStart.OpenTv
    }

    /** [load] tried again every [stepMs] until it answers, for at most [timeoutMs]; null when it never did. */
    suspend fun <T : Any> untilAnswered(timeoutMs: Long = SERVER_WAIT_MS, stepMs: Long = RETRY_STEP_MS, load: suspend () -> T): T? =
        withTimeoutOrNull(timeoutMs) {
            var result: T? = null
            while (result == null) {
                result = try {
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(stepMs)
                    null
                }
            }
            result
        }
}
