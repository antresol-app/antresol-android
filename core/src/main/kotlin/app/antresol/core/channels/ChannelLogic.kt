package app.antresol.core.channels

import app.antresol.core.api.ApiException
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelCategory
import app.antresol.core.model.ChannelProgram
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Which channels a list shows: all of them, the profile's favourites, or one category. The list
 * keeps the number order whatever the filter (a TV's list never reorders). [key] survives process
 * death and intents.
 */
sealed interface ChannelFilter {
    val key: String

    data object All : ChannelFilter {
        override val key = "all"
    }

    data object Favorites : ChannelFilter {
        override val key = "favorites"
    }

    /** One category: [id] as [app.antresol.core.model.ChannelCategory.id] (`ai:news`, `source:3`, `group:<name>`). */
    data class Category(val id: String) : ChannelFilter {
        override val key = "cat:$id"
    }

    companion object {
        fun fromKey(key: String?): ChannelFilter = when {
            key == null || key == All.key -> All
            key == Favorites.key -> Favorites
            key.startsWith("cat:") -> Category(key.removePrefix("cat:"))
            // What this filter was called while a category was only the channel's `category` text.
            key.startsWith("theme:") -> Category(Channels.groupId(key.removePrefix("theme:")))
            else -> All
        }
    }
}

/**
 * One entry of the category list of a TV's channel list: a [filter], how many channels it lets
 * through, and for a [ChannelFilter.Category] its [label] (the viewer's language; empty: channels
 * without a category) and [kind] ([app.antresol.core.model.ChannelCategory.KIND_AI] and so on).
 * «All» and «Favourites» have neither: the apps name them.
 */
data class ChannelFilterOption(val filter: ChannelFilter, val count: Int, val label: String? = null, val kind: String? = null)

/**
 * The player's mini-guide: the channels of the list the player was opened from, by number, and the
 * one being watched ([currentIndex]). Empty while the channels are loading.
 */
data class MiniGuide(val channels: List<Channel>, val currentIndex: Int) {
    companion object {
        val Empty = MiniGuide(emptyList(), 0)
    }
}

/**
 * What one row of the channel list shows (a TV's channel list: no times, no «next»): the number,
 * the channel's name with its favourite star, the programme on air on one line, its progress
 * (0…1, null without a programme) and the picture.
 */
data class ChannelRowContent(
    val number: Int,
    val name: String,
    val favorite: Boolean,
    val programme: String?,
    val progress: Float?,
    val art: String?,
)

/** Pure rules of the TV tab and the live player, shared by both apps (mirrors the web's `channels.ts`). */
object Channels {
    /** How often the channel list and a channel's guide are reloaded while visible. */
    const val REFRESH_MS = 30_000L

    /** Hours of guide the player asks for. */
    const val GUIDE_HOURS = 6

    fun sortedByNumber(channels: List<Channel>): List<Channel> = channels.sortedWith(compareBy({ it.number }, { it.id }))

    /** The id of a playlist group or an own channel's category [name] (empty: no category). */
    fun groupId(name: String): String = "group:$name"

    /** The category id that means «no category»: channels that are in none (a source did not group them). */
    val NO_CATEGORY: String = groupId("")

    /**
     * A channel's category ids: the server's [Channel.categories], and for a server that sends none
     * (older, or the channel is in none) its own `category` as a group, as the web does.
     */
    fun categoriesOf(channel: Channel): List<String> =
        channel.categories ?: channel.category.trim().takeIf { it.isNotEmpty() }?.let { listOf(groupId(it)) }.orEmpty()

    private fun inCategory(channel: Channel, id: String): Boolean =
        if (id == NO_CATEGORY) categoriesOf(channel).isEmpty() else id in categoriesOf(channel)

    /**
     * The category list of a channel list, with the channels each really has: the server's list
     * ([server], for the language, the kinds and the order: AI topics, sources, groups) when it sent
     * one, else the channels' own categories in the order of their lowest channel number (older
     * servers; a trailing «no category» entry when some channels have none and others are grouped).
     * A category nobody is in is left out.
     */
    fun categoryList(channels: List<Channel>, server: List<ChannelCategory>?): List<ChannelCategory> {
        val counts = HashMap<String, Int>()
        val firstSeen = ArrayList<String>()
        var ungrouped = 0
        for (channel in sortedByNumber(channels)) {
            val ids = categoriesOf(channel)
            if (ids.isEmpty()) ungrouped++
            for (id in ids) if (counts.merge(id, 1, Int::plus) == 1) firstSeen += id
        }
        if (server != null) {
            return server.mapNotNull { category -> counts[category.id]?.let { category.copy(count = it) } }
        }
        // Without the server's list only a group can be named (its own text); a topic or source id has no name here.
        val groups = firstSeen.filter { it.startsWith("group:") }
            .map { id -> ChannelCategory(id, id.removePrefix("group:"), ChannelCategory.KIND_GROUP, counts.getValue(id)) }
        return if (ungrouped > 0 && groups.isNotEmpty()) groups + ChannelCategory(NO_CATEGORY, "", ChannelCategory.KIND_GROUP, ungrouped) else groups
    }

    /**
     * The category list of a big channel list (an external playlist has dozens of groups): «All» and
     * «Favourites» first, then [categoryList], each with its channel count.
     */
    fun filterOptions(channels: List<Channel>, server: List<ChannelCategory>? = null): List<ChannelFilterOption> = buildList {
        add(ChannelFilterOption(ChannelFilter.All, channels.size))
        add(ChannelFilterOption(ChannelFilter.Favorites, channels.count { it.favorite }))
        categoryList(channels, server).forEach { add(ChannelFilterOption(ChannelFilter.Category(it.id), it.count, it.name, it.kind)) }
    }

    /**
     * What the category filter [filter] is called: its name in [categories] (the server's list), else
     * a group's own name; null when it has none to show (no category, or an id nobody named).
     */
    fun categoryName(filter: ChannelFilter.Category, categories: List<ChannelCategory>): String? =
        categories.firstOrNull { it.id == filter.id }?.name?.takeIf { it.isNotBlank() }
            ?: filter.id.takeIf { it.startsWith("group:") }?.removePrefix("group:")?.takeIf { it.isNotBlank() }

    /** The channels [filter] lets through, by number. */
    fun filter(channels: List<Channel>, filter: ChannelFilter): List<Channel> = sortedByNumber(
        when (filter) {
            ChannelFilter.All -> channels
            ChannelFilter.Favorites -> channels.filter { it.favorite }
            is ChannelFilter.Category -> channels.filter { inCategory(it, filter.id) }
        },
    )

    /** [filter] while it still means something (a category that is gone falls back to «All»). */
    fun validFilter(channels: List<Channel>, filter: ChannelFilter): ChannelFilter = when (filter) {
        is ChannelFilter.Category -> if (channels.any { inCategory(it, filter.id) }) filter else ChannelFilter.All
        else -> filter
    }

    /**
     * The channel [step] places from [currentId] in number order, wrapping at both ends (+1 is
     * "channel up", the next number). A channel not in [channels] (unfavourited while watching)
     * goes to the nearest number in the [step] direction. Null when there is no other channel.
     */
    fun neighbour(channels: List<Channel>, currentId: Long, step: Int, currentNumber: Int? = null): Channel? {
        val sorted = sortedByNumber(channels)
        val index = sorted.indexOfFirst { it.id == currentId }
        if (index < 0) {
            if (sorted.isEmpty()) return null
            val number = currentNumber ?: return sorted.first()
            return if (step > 0) {
                sorted.firstOrNull { it.number > number } ?: sorted.first()
            } else {
                sorted.lastOrNull { it.number < number } ?: sorted.last()
            }
        }
        if (sorted.size < 2) return null
        return sorted[Math.floorMod(index + step, sorted.size)]
    }

    /** The profile's last channel while it can still see it (focus, highlight, «back to N»); else null. */
    fun lastChannel(channels: List<Channel>, lastId: Long?): Channel? =
        lastId?.let { id -> channels.firstOrNull { it.id == id } }

    /** The mini-guide for [currentId]: the list it was opened from ([channels], already filtered). */
    fun miniGuide(channels: List<Channel>, currentId: Long): MiniGuide {
        if (channels.isEmpty()) return MiniGuide.Empty
        val sorted = sortedByNumber(channels)
        return MiniGuide(sorted, sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
    }

    /**
     * Epoch milliseconds of an ISO 8601 time from the server, or null when it cannot be read. The
     * server writes Go's RFC 3339 in its own zone ("2026-11-18T13:42:00+03:00" off UTC, "…Z" on it,
     * fractional seconds when it has them). `Instant.parse` takes only "Z" on older Android runtimes
     * (java.time before JDK 12's ISO_INSTANT), so an offset is read as an offset date-time first.
     */
    fun epochMs(iso: String): Long? = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** How far [program] is at [nowMs], 0…1 ((now − start)/(end − start)); 0 for unknown times. */
    fun progress(program: ChannelProgram, nowMs: Long): Float {
        val start = program.startMs ?: return 0f
        val end = program.endMs ?: return 0f
        if (end <= start) return 0f
        return ((nowMs - start).toDouble() / (end - start)).coerceIn(0.0, 1.0).toFloat()
    }

    /** The programme on air at [nowMs] in a guide and the one after it (the first that has not ended). */
    fun onAir(programs: List<ChannelProgram>, nowMs: Long): Pair<ChannelProgram?, ChannelProgram?> {
        val index = programs.indexOfFirst { (it.endMs ?: Long.MAX_VALUE) > nowMs }
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
        val ended = (now.endMs ?: Long.MAX_VALUE) <= nowMs
        val started = (next.startMs ?: Long.MAX_VALUE) <= nowMs
        return if (ended && started) next else now
    }

    /** The programme on air at [atMs], or null (a gap, or [atMs] outside the list). */
    fun programAt(programs: List<ChannelProgram>, atMs: Long): ChannelProgram? = programs.firstOrNull { program ->
        val start = program.startMs ?: return@firstOrNull false
        val end = program.endMs ?: return@firstOrNull false
        atMs in start until end
    }

    /** [program] is on air at [nowMs]. */
    fun isOnAir(program: ChannelProgram, nowMs: Long): Boolean {
        val start = program.startMs ?: return false
        val end = program.endMs ?: return false
        return nowMs in start until end
    }

    /**
     * A programme time as shown: rounded to the nearest minute (the server's times carry seconds;
     * 23:50:38 reads «23:51», so a guide block labelled with it starts where the ruler says).
     * Positions on the guide stay exact; only the text is rounded.
     */
    fun displayMs(ms: Long): Long = Math.floorDiv(ms + 30_000L, 60_000L) * 60_000L

    /** The displayed (rounded) time of an ISO 8601 instant, or null when it cannot be read. */
    fun displayMs(iso: String): Long? = epochMs(iso)?.let(::displayMs)

    /** A programme's length in whole minutes as shown: between its rounded start and end. */
    fun displayMinutes(program: ChannelProgram): Int? {
        val start = displayMs(program.start) ?: return null
        val end = displayMs(program.end) ?: return null
        return ((end - start) / 60_000L).toInt()
    }

    /** The content of [channel]'s row in the list at [nowMs]. */
    fun rowContent(channel: Channel, nowMs: Long): ChannelRowContent {
        val program = showing(channel, nowMs)
        return ChannelRowContent(
            number = channel.number,
            name = channel.name,
            favorite = channel.favorite,
            programme = program?.let(::programLine),
            progress = program?.let { progress(it, nowMs) },
            art = program?.let { it.backdrop.ifEmpty { it.poster } }?.ifEmpty { null },
        )
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
