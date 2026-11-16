package app.antresol.mobile.ui.channels

import app.antresol.ui.channels.ChannelLogo
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.core.channels.Channels
import app.antresol.core.channels.Epg
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelProgram
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R
import app.antresol.ui.channels.LocalMinuteWidth
import app.antresol.ui.channels.guideDayLabel
import app.antresol.ui.channels.minutesToDp
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.stickyLabel
import app.antresol.ui.channels.timeFormat
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

private val ChannelColumnWidth = 112.dp
private val RowHeight = 68.dp
private val RulerHeight = 32.dp

/**
 * «Телегид» on the phone: a sticky channel column (number, name, star), a time ruler with
 * half-hour ticks, programme blocks as wide as they are long, the now line; past programmes dimmed,
 * the one on air highlighted. Touch scrolls both ways (all rows share one horizontal scroll); a
 * programme opens its details, a channel tunes in. Near the loaded end the next page is asked for.
 */
@Composable
fun GuideGrid(
    rows: List<ChannelEpgRow>,
    fromMs: Long,
    toMs: Long,
    nowMs: Long,
    lastChannelId: Long?,
    onPick: (GuidePick) -> Unit,
    onWatchChannel: (Long) -> Unit,
    onNearEnd: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val minute = remember(rows.isNotEmpty()) { Epg.minuteWidth(rows, 140f, 4f, 24f).dp }
    val tick = Epg.tickMs(minute.value, 90f)
    val width = (toMs - fromMs).minutesToDp(minute)
    LaunchedEffect(fromMs, minute) {
        // Open on now, with two ticks of the past in view.
        val target = with(density) { (nowMs - 2 * tick - fromMs).coerceAtLeast(0).minutesToDp(minute).roundToPx() }
        scroll.scrollTo(target)
    }
    LaunchedEffect(scroll) {
        // Every scroll position near the end asks, not only arriving there: a page that failed is
        // asked for again by scrolling on ([onNearEnd] ignores the call while a page is loading).
        snapshotFlow { if (scroll.maxValue > 0 && scroll.maxValue - scroll.value < scroll.viewportSize * 2) scroll.value else -1 }
            .collect { near -> if (near >= 0) onNearEnd() }
    }
    // The time at the visible left edge. Read in layout and derived state only, never in this
    // composition: the grid must not recompose with every frame of a scroll.
    val leftMs = remember(fromMs, minute, density) {
        { fromMs + with(density) { (scroll.value.toDp() / minute * 60_000f).toLong() } }
    }
    // The ruler's day changes on a quarter of an hour at the earliest (time zones), not with every pixel.
    val dayAtMs by remember(leftMs) { derivedStateOf { Epg.floorTo(leftMs(), Epg.SLOT_MS / 2) } }
    // Only the programmes around the view are composed (a guide paged two days ahead has thousands).
    val composed by remember(leftMs, minute, density) {
        derivedStateOf {
            val viewMs = with(density) { (scroll.viewportSize.toDp() / minute * 60_000f).toLong() }
            Epg.composedWindow(leftMs(), viewMs)
        }
    }
    val context = LocalContext.current
    val clock = remember(context) { timeFormat(context) }
    val reserveLogo = remember(rows) { app.antresol.ui.channels.reserveLogoSlot(rows.map { it.logoUrl }) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMinuteWidth provides minute,
        app.antresol.ui.channels.LocalReserveChannelLogo provides reserveLogo,
    ) {
    Column(modifier) {
        Row(Modifier.height(RulerHeight), verticalAlignment = Alignment.CenterVertically) {
            Text(
                guideDayLabel(dayAtMs, nowMs),
                style = Antresol.typography.caption.copy(color = AntresolColors.Label),
                maxLines = 1,
                modifier = Modifier.width(ChannelColumnWidth).padding(start = 16.dp),
            )
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                Ruler(fromMs, toMs, composed, nowMs, width, tick, clock)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize().testTag("guide-grid")) {
            items(rows, key = { it.id }) { row ->
                Row(Modifier.height(RowHeight)) {
                    GuideChannelCell(row, last = row.id == lastChannelId, onClick = { onWatchChannel(row.id) })
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                        Timeline(row, fromMs, toMs, composed, nowMs, width, leftMs, clock) { program ->
                            onPick(GuidePick(row.id, row.number, row.name, row.favorite, program))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
            }
        }
    }
    }
}

@Composable
private fun GuideChannelCell(row: ChannelEpgRow, last: Boolean, onClick: () -> Unit) {
    val urls = app.antresol.mobile.ui.LocalMediaUrls.current
    Column(
        modifier = Modifier
            .width(ChannelColumnWidth)
            .fillMaxHeight()
            .focusClickable(AntresolShapes.smallCard, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                row.number.toString(),
                style = Antresol.typography.title.copy(
                    color = if (last) AntresolColors.Accent else AntresolColors.LabelSecondary,
                    fontFeatureSettings = "tnum",
                ),
            )
            ChannelLogo(urls?.image(row.logoUrl), height = 20.dp)
            if (row.favorite) FavoriteMark()
        }
        Text(
            row.name,
            style = Antresol.typography.caption.copy(color = AntresolColors.Label, hyphens = Hyphens.None),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The ticks with their times (those around the view, see [Epg.composedWindow]) and the now mark. */
@Composable
private fun Ruler(fromMs: Long, toMs: Long, composed: LongRange, nowMs: Long, width: Dp, stepMs: Long, clock: java.text.DateFormat) {
    Box(Modifier.width(width).fillMaxHeight()) {
        RulerTicks(fromMs, minOf(toMs, composed.last), maxOf(fromMs, composed.first), stepMs, clock)
        if (nowMs in fromMs until toMs) {
            Box(
                Modifier
                    .offset(x = (nowMs - fromMs).minutesToDp() - 1.dp)
                    .align(Alignment.BottomStart)
                    .width(2.dp)
                    .height(14.dp)
                    .background(AntresolColors.Red),
            )
        }
    }
}

/** The ticks from [firstMs] to [toMs]; apart from the now mark, so the clock's tick does not redraw them. */
@Composable
private fun BoxScope.RulerTicks(fromMs: Long, toMs: Long, firstMs: Long, stepMs: Long, clock: java.text.DateFormat) {
    var tick = Epg.floorTo(firstMs, stepMs)
    if (tick < firstMs) tick += stepMs
    while (tick < toMs) {
        val x = (tick - fromMs).minutesToDp()
        Box(Modifier.offset(x = x).align(Alignment.BottomStart).width(1.dp).height(8.dp).background(AntresolColors.LabelTertiary))
        Text(
            programClock(clock, tick),
            style = Antresol.typography.caption,
            modifier = Modifier.offset(x = x + 5.dp).align(Alignment.CenterStart),
        )
        tick += stepMs
    }
}

@Composable
private fun Timeline(
    row: ChannelEpgRow,
    fromMs: Long,
    toMs: Long,
    composed: LongRange,
    nowMs: Long,
    width: Dp,
    leftMs: () -> Long,
    clock: java.text.DateFormat,
    onPick: (ChannelProgram) -> Unit,
) {
    val programs = remember(row.programs, fromMs, toMs, composed) {
        Epg.overlapping(Epg.visible(row, fromMs, toMs), composed.first, composed.last)
    }
    Box(Modifier.width(width).fillMaxHeight()) {
        programs.forEach { program ->
            val programStart = program.startMs ?: return@forEach
            val programEnd = program.endMs ?: return@forEach
            val start = programStart.coerceAtLeast(fromMs)
            val end = programEnd.coerceAtMost(toMs)
            // Keyed: a block keeps its state when the composed window moves along the row.
            key(program.id.ifEmpty { program.start }) {
                GuideBlock(
                    program = program,
                    onAir = nowMs in programStart until programEnd,
                    past = programEnd <= nowMs,
                    startMs = start,
                    endMs = end,
                    leftMs = leftMs,
                    clock = clock,
                    onClick = { onPick(program) },
                    modifier = Modifier
                        .offset(x = (start - fromMs).minutesToDp())
                        .width((end - start).minutesToDp())
                        .fillMaxHeight()
                        .padding(start = 1.dp, end = 1.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
        if (nowMs in fromMs until toMs) {
            Box(
                Modifier
                    .offset(x = (nowMs - fromMs).minutesToDp() - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(AntresolColors.Red),
            )
        }
    }
}

/**
 * A programme's block from [startMs] to [endMs] (cut to the loaded timeline). It takes whether the
 * programme is [onAir] or [past], not the time, so the clock's tick recomposes only the blocks that change.
 */
@Composable
private fun GuideBlock(
    program: ChannelProgram,
    onAir: Boolean,
    past: Boolean,
    startMs: Long,
    endMs: Long,
    leftMs: () -> Long,
    clock: java.text.DateFormat,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val fill = when {
        onAir -> AntresolColors.FillPrimary
        past -> AntresolColors.FillQuaternary
        else -> AntresolColors.FillTertiary
    }
    val text = if (past) AntresolColors.LabelTertiary else AntresolColors.Label
    val startClock = remember(program, clock) { program.startMs?.let { programClock(clock, it) }.orEmpty() }
    val endClock = remember(program, clock) { program.endMs?.let { programClock(clock, it) }.orEmpty() }
    val time = stringResource(R.string.tv_time, startClock, endClock)
    val description = listOf(Channels.programLine(program), time).joinToString(", ")
    val minute = LocalMinuteWidth.current
    Box(
        modifier
            .clip(AntresolShapes.badge)
            .background(fill)
            .focusClickable(AntresolShapes.badge, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier.stickyLabel(
                blockWidth = { (endMs - startMs).minutesToDp(minute).toPx() },
                inset = { Epg.stickyLabelOffsetMs(startMs, endMs, leftMs()).minutesToDp(minute).toPx() },
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                program.title,
                style = (if (onAir) Antresol.typography.bodyEmphasis else Antresol.typography.body).copy(color = text, fontSize = Antresol.typography.caption.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                startClock,
                style = Antresol.typography.caption.copy(color = if (past) AntresolColors.LabelTertiary else AntresolColors.LabelSecondary, fontSize = Antresol.typography.badge.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
