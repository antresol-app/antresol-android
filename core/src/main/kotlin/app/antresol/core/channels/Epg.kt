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
     * changed), each with its programmes from both, by start, without duplicates (same id).
     */
    fun merge(rows: List<ChannelEpgRow>, page: ChannelEpg): List<ChannelEpgRow> {
        val old = rows.associateBy { it.id }
        return page.channels.map { row ->
            val before = old[row.id]?.programs.orEmpty()
            val programs = (before + row.programs)
                .distinctBy { it.id.ifEmpty { it.start + it.itemId } }
                .sortedBy { Channels.epochMs(it.start) ?: 0L }
            row.copy(programs = programs)
        }
    }

    /**
     * The programme a vertical move lands on in [programs]: the one covering [anchorMs] (the time
     * position the viewer keeps), else the nearest one; null for an empty row.
     */
    fun landing(programs: List<ChannelProgram>, anchorMs: Long): ChannelProgram? {
        if (programs.isEmpty()) return null
        Channels.programAt(programs, anchorMs)?.let { return it }
        return programs.minByOrNull { program ->
            val start = Channels.epochMs(program.start) ?: Long.MAX_VALUE
            val end = Channels.epochMs(program.end) ?: Long.MAX_VALUE
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
        val start = Channels.epochMs(program.start) ?: return visibleFromMs
        val end = Channels.epochMs(program.end) ?: return start
        return start.coerceAtLeast(visibleFromMs).coerceAtMost(end - 1)
    }

    /** The programmes of [row] that overlap `[fromMs, toMs)`. */
    fun visible(row: ChannelEpgRow, fromMs: Long, toMs: Long): List<ChannelProgram> = row.programs.filter { program ->
        val start = Channels.epochMs(program.start) ?: return@filter false
        val end = Channels.epochMs(program.end) ?: return@filter false
        end > fromMs && start < toMs
    }
}
