package app.antresol.core.channels

import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelProgram
import java.time.Instant

/**
 * The programme guide grid's rules (spec «Programme guide»): the window it shows, how pages of
 * `GET /api/channels/epg` merge, and where a D-pad move lands (vertical moves keep the time).
 */
object Epg {
    const val SLOT_MS = 30 * 60_000L
    const val HOUR_MS = 60 * 60_000L

    /** The guide opens this far before now (the schedule is kept 7 days back; the guide shows 3 h). */
    const val BACK_MS = 3 * HOUR_MS

    /** And can be paged this far ahead (the schedule exists 48 h ahead). */
    const val AHEAD_MS = 47 * HOUR_MS

    /** Hours per page request. */
    const val PAGE_HOURS = 6

    /** [ms] rounded down to the half hour (the ruler's ticks). */
    fun floorSlot(ms: Long): Long = Math.floorDiv(ms, SLOT_MS) * SLOT_MS

    /** Where the guide's timeline starts: 3 h before the half hour now is in. */
    fun origin(nowMs: Long): Long = floorSlot(nowMs) - BACK_MS

    /** The last instant the guide may page to. */
    fun limit(nowMs: Long): Long = floorSlot(nowMs) + AHEAD_MS

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /**
     * [page] merged into [rows]: the channels in [page]'s order (the list order, which may have
     * changed), each with its programmes from both, by start, each programme once ([joined]).
     * [page] is the newer answer: what [rows] have that starts inside its window `[from, to)` is
     * dropped, so a schedule the server rebuilt since (it reuses the programme ids) is shown as the
     * page has it, not mixed with the old one.
     */
    fun merge(rows: List<ChannelEpgRow>, page: ChannelEpg): List<ChannelEpgRow> {
        val old = rows.associateBy { it.id }
        val from = Channels.epochMs(page.from)
        val to = Channels.epochMs(page.to)
        return page.channels.map { row ->
            val before = old[row.id]?.programs.orEmpty().filterNot { program ->
                val start = program.startMs
                from != null && to != null && start != null && start >= from && start < to
            }
            row.copy(programs = joined(before + row.programs))
        }
    }

    /**
     * What tells a channel's programmes apart, for the merge, a guide's blocks and the focus: the id,
     * or the start when the source gives none ([joined] leaves one programme per key and per start).
     */
    fun key(program: ChannelProgram): String = program.id.ifEmpty { program.start }

    /**
     * [programs] (the older page's first) by start, each programme once. A programme across a page's
     * edge comes with both pages, and the server joins the parts of a film (its files, back to back)
     * within a page only: the same programme may come again with a later end (its second part began
     * in the next page), or as its remaining parts with their own id and start. So:
     * - of two programmes with one key or one start the one given later (the newer page's) stays:
     *   a page that still has a programme's first part has at least as much of it as the page before,
     *   and after a rebuild of the schedule the id is another programme's;
     * - one of the same title that starts before the one before it ends is the rest of it. Unless it
     *   is exactly as long: then it is the same files in another order, the end of this showing
     *   joined by the server with the beginning of the title's next showing, and only what is after
     *   this showing's end is kept, as a programme of its own;
     * - a repeat of a title starts where the first showing ends and stays a programme of its own.
     * - a library title that is still on when another one starts (the page before had it across its
     *   edge, and the schedule was rebuilt since) ends there. Programmes of an external guide have
     *   no item and may overlap.
     */
    fun joined(programs: List<ChannelProgram>): List<ChannelProgram> {
        // One programme per key, the one given last.
        val newest = HashMap<String, ChannelProgram>()
        for (program in programs) newest[key(program)] = program
        val out = ArrayList<ChannelProgram>(programs.size)
        // The sort is stable: of two with one start the one given later comes second.
        for (next in programs.sortedBy { it.startMs ?: 0L }) {
            if (key(next).isNotEmpty() && newest[key(next)] !== next) continue
            val last = out.lastOrNull()
            val lastStart = last?.startMs ?: Long.MIN_VALUE
            val lastEnd = last?.endMs ?: Long.MIN_VALUE
            val nextStart = next.startMs ?: Long.MAX_VALUE
            val nextEnd = next.endMs ?: Long.MIN_VALUE
            when {
                last == null -> out += next
                (next.start.isNotEmpty() && last.start == next.start) || nextStart == lastStart -> out[out.lastIndex] = next
                last.itemId != 0L && last.itemId == next.itemId && nextStart < lastEnd -> when {
                    nextEnd <= lastEnd -> Unit
                    nextStart > lastStart && nextEnd - nextStart == lastEnd - lastStart -> out += next.copy(start = last.end)
                    else -> out[out.lastIndex] = last.copy(end = next.end)
                }
                // Two library titles never run at once: the one that starts later is what the newer page says.
                last.itemId != 0L && next.itemId != 0L && nextStart < lastEnd -> {
                    out[out.lastIndex] = last.copy(end = next.start)
                    out += next
                }
                else -> out += next
            }
        }
        return out
    }

    /**
     * The programme a vertical move lands on in [programs]: the one covering [anchorMs] (the time
     * position the viewer keeps), else the nearest one; null for an empty row.
     */
    fun landing(programs: List<ChannelProgram>, anchorMs: Long): ChannelProgram? {
        if (programs.isEmpty()) return null
        Channels.programAt(programs, anchorMs)?.let { return it }
        return programs.minByOrNull { program ->
            val start = program.startMs ?: Long.MAX_VALUE
            val end = program.endMs ?: Long.MAX_VALUE
            when {
                anchorMs < start -> start - anchorMs
                anchorMs >= end -> anchorMs - end + 1
                else -> 0L
            }
        }
    }

    /**
     * The time position to keep after focusing [program] by a horizontal move: its start, but not
     * before [visibleFromMs] (a long programme that began off-screen keeps the visible edge).
     */
    fun anchorFor(program: ChannelProgram, visibleFromMs: Long): Long {
        val start = program.startMs ?: return visibleFromMs
        val end = program.endMs ?: return start
        return start.coerceAtLeast(visibleFromMs).coerceAtMost(end - 1)
    }

    /** The median length of the programmes in [rows], in minutes (30 when unknown). */
    fun typicalMinutes(rows: List<ChannelEpgRow>): Float {
        val lengths = rows.flatMap { it.programs }.mapNotNull { program ->
            val start = program.startMs ?: return@mapNotNull null
            val end = program.endMs ?: return@mapNotNull null
            ((end - start) / 60_000f).takeIf { it > 0f }
        }.sorted()
        return if (lengths.isEmpty()) 30f else lengths[lengths.size / 2]
    }

    /**
     * The width of a minute (dp): a typical programme gets about [typicalWidth] dp, within
     * [min]…[max] — films of 90 min at the minimum scale, short clips stretched to stay readable.
     */
    fun minuteWidth(rows: List<ChannelEpgRow>, typicalWidth: Float, min: Float, max: Float): Float =
        (typicalWidth / typicalMinutes(rows)).coerceIn(min, max)

    /** The ruler's tick: the shortest of 5, 10, 15, 30, 60 minutes at least [minSpacing] dp apart. */
    fun tickMs(minuteWidth: Float, minSpacing: Float): Long {
        val minutes = listOf(5, 10, 15, 30, 60).firstOrNull { it * minuteWidth >= minSpacing } ?: 60
        return minutes * 60_000L
    }

    /** [ms] rounded down to a multiple of [stepMs]. */
    fun floorTo(ms: Long, stepMs: Long): Long = Math.floorDiv(ms, stepMs) * stepMs

    /** The programmes of [row] that overlap `[fromMs, toMs)`. */
    fun visible(row: ChannelEpgRow, fromMs: Long, toMs: Long): List<ChannelProgram> = overlapping(row.programs, fromMs, toMs)

    /** The [programs] that overlap `[fromMs, toMs)`. */
    fun overlapping(programs: List<ChannelProgram>, fromMs: Long, toMs: Long): List<ChannelProgram> = programs.filter { program ->
        val start = program.startMs ?: return@filter false
        val end = program.endMs ?: return@filter false
        end > fromMs && start < toMs
    }

    /**
     * The stretch of the timeline whose programmes a guide composes when [leftMs] is at its left
     * edge and [viewMs] is in view: the view and as much again (at least an hour) on each side, so
     * what a scroll or a D-pad move reveals is already there. It starts on a whole hour, so it stays
     * the same while the guide scrolls within that hour.
     */
    fun composedWindow(leftMs: Long, viewMs: Long): LongRange {
        val margin = maxOf(viewMs, HOUR_MS)
        val left = floorTo(leftMs, HOUR_MS)
        return (left - margin)..(left + HOUR_MS + viewMs + margin)
    }

    /**
     * How far (ms of timeline) a block's label moves right so it stays at the visible left edge
     * [leftMs] when the programme began before it. Always pinned to the edge, so every row's label
     * has the same inset; a short remainder shortens the title («…») rather than letting it slide
     * under the channel column. 0 when the block starts in view; at most the block's length.
     */
    fun stickyLabelOffsetMs(startMs: Long, endMs: Long, leftMs: Long): Long {
        if (leftMs <= startMs) return 0L
        return minOf(leftMs - startMs, (endMs - startMs).coerceAtLeast(0L))
    }
}
