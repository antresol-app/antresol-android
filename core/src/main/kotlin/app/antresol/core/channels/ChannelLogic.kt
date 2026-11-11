package app.antresol.core.channels

import app.antresol.core.api.ApiException
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import java.time.Instant
import java.time.format.DateTimeParseException

/** A section of the TV tab: the channels of one category, by number. */
data class ChannelSection(val category: String, val channels: List<Channel>)

/**
 * The player's mini-guide: the channels of the current channel's section by number, and where the
 * strip starts ([startIndex], the channel being watched). Empty while the channels are loading.
 */
data class MiniGuide(val category: String, val channels: List<Channel>, val startIndex: Int) {
    companion object {
        val Empty = MiniGuide("", emptyList(), 0)
    }
}

/** Pure rules of the TV tab and the live player, shared by both apps (mirrors the web's `channels.ts`). */
object Channels {
    /** How often the channel list and a channel's guide are reloaded while visible. */
    const val REFRESH_MS = 30_000L

    /** Hours of guide the player asks for. */
    const val GUIDE_HOURS = 6

    /** The channels by category, sections in the order of their lowest number, channels by number. */
    fun sections(channels: List<Channel>): List<ChannelSection> =
        sortedByNumber(channels)
            .groupBy { it.category }
            .map { (category, list) -> ChannelSection(category, list) }

    fun sortedByNumber(channels: List<Channel>): List<Channel> = channels.sortedWith(compareBy({ it.number }, { it.id }))

    /**
     * The channel [step] places from [currentId] in number order, wrapping at both ends (+1 is
     * "channel up", the next number). Null when there is no other channel to switch to.
     */
    fun neighbour(channels: List<Channel>, currentId: Long, step: Int): Channel? {
        val sorted = sortedByNumber(channels)
        if (sorted.size < 2) return null
        val index = sorted.indexOfFirst { it.id == currentId }
        if (index < 0) return sorted.first()
        val target = Math.floorMod(index + step, sorted.size)
        return sorted[target]
    }

    /** The last watched channel while the profile can still see it, for «Continue»; else null. */
    fun continueWith(channels: List<Channel>, lastId: Long?): Channel? =
        lastId?.let { id -> channels.firstOrNull { it.id == id } }

    /**
     * The strip of the player's mini-guide for [currentId]: its section's channels by number,
     * starting at it. A channel that is not in the list (yet) gives every channel from the first.
     */
    fun miniGuide(channels: List<Channel>, currentId: Long): MiniGuide {
        if (channels.isEmpty()) return MiniGuide.Empty
        val current = channels.firstOrNull { it.id == currentId }
            ?: return MiniGuide("", sortedByNumber(channels), 0)
        val section = sortedByNumber(channels.filter { it.category == current.category })
        return MiniGuide(current.category, section, section.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
    }

    /** Epoch milliseconds of an ISO 8601 time from the server, or null when it cannot be read. */
    fun epochMs(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** How far [program] is at [nowMs], 0…1 ((now − start)/(end − start)); 0 for unknown times. */
    fun progress(program: ChannelProgram, nowMs: Long): Float {
        val start = epochMs(program.start) ?: return 0f
        val end = epochMs(program.end) ?: return 0f
        if (end <= start) return 0f
        return ((nowMs - start).toDouble() / (end - start)).coerceIn(0.0, 1.0).toFloat()
    }

    /** The programme on air at [nowMs] in a guide and the one after it (the first that has not ended). */
    fun onAir(programs: List<ChannelProgram>, nowMs: Long): Pair<ChannelProgram?, ChannelProgram?> {
        val index = programs.indexOfFirst { (epochMs(it.end) ?: Long.MAX_VALUE) > nowMs }
        if (index < 0) return null to null
        return programs[index] to programs.getOrNull(index + 1)
    }

    /**
     * What a channel card shows at [nowMs]: its `now`, or its `next` once `now` has ended (the list
     * is refreshed only every [REFRESH_MS], short programmes change in between).
     */
    fun showing(channel: Channel, nowMs: Long): ChannelProgram? {
        val now = channel.now ?: return null
        val next = channel.next ?: return now
        val ended = (epochMs(now.end) ?: Long.MAX_VALUE) <= nowMs
        val started = (epochMs(next.start) ?: Long.MAX_VALUE) <= nowMs
        return if (ended && started) next else now
    }

    /** "Title · S1 E5 · Episode 5": the programme's title with its subtitle, when it has one. */
    fun programLine(program: ChannelProgram): String =
        listOf(program.title, program.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
}

/** What the live player does after its stream could not be opened. */
sealed interface LiveRetry {
    /** The channel's encoder is starting: try again after [delayMs] ("Starting the channel…"). */
    data class After(val delayMs: Long) : LiveRetry

    /** Show the server's [message] (or a generic one when null) with "Try Again". */
    data class Fail(val message: String?) : LiveRetry
}

/**
 * Retry rules for the live playlist. The server answers 503 with `Retry-After: 2` while a channel's
 * encoder starts (retried automatically), and 503 with `Retry-After: 30` when too many channels run
 * at once, 403 when a kid's screen time is over, 404 for a channel that is gone (all shown to the
 * user). Network errors are retried a few times too.
 */
object LiveStartPolicy {
    /** Retry-After values up to this are "starting", longer ones a real refusal. */
    const val MAX_AUTO_RETRY_AFTER_SEC = 5L

    /** Automatic attempts before the player gives up and shows the error. */
    const val MAX_ATTEMPTS = 15

    private const val DEFAULT_DELAY_MS = 2_000L

    /** [attempt] is the number of failed attempts so far, 1 for the first failure. */
    fun decide(error: Throwable, attempt: Int): LiveRetry {
        val api = error as? ApiException
        if (attempt >= MAX_ATTEMPTS) return LiveRetry.Fail(api?.message)
        if (api == null) return LiveRetry.After(DEFAULT_DELAY_MS)
        val retryAfter = api.retryAfterSec
        return if (api.status == 503 && retryAfter != null && retryAfter <= MAX_AUTO_RETRY_AFTER_SEC) {
            LiveRetry.After(maxOf(retryAfter * 1_000L, 500L))
        } else {
            LiveRetry.Fail(api.message)
        }
    }
}
