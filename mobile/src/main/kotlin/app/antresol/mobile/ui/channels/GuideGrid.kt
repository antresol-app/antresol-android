package app.antresol.mobile.ui.channels

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.core.channels.Channels
import app.antresol.core.channels.Epg
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelProgram
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R
import app.antresol.ui.channels.guideDayLabel
import app.antresol.ui.channels.programClock
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant

private val ChannelColumnWidth = 96.dp
private val RowHeight = 68.dp
private val RulerHeight = 32.dp

/** Width of one minute: half an hour is 120 dp. */
private val MinuteWidth = 4.dp

private fun Long.minutesToDp(): Dp = MinuteWidth * (this / 60_000f)

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
    val width = (toMs - fromMs).minutesToDp()
    LaunchedEffect(fromMs) {
        // Open on now, with half an hour of the past in view.
        val target = with(density) { (nowMs - Epg.SLOT_MS - fromMs).coerceAtLeast(0).minutesToDp().roundToPx() }
        scroll.scrollTo(target)
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue - scroll.value < scroll.viewportSize * 2 }
            .distinctUntilChanged()
            .collect { near -> if (near && scroll.maxValue > 0) onNearEnd() }
    }
    val leftMs = fromMs + with(density) { (scroll.value.toDp() / MinuteWidth * 60_000f).toLong() }
    Column(modifier) {
        Row(Modifier.height(RulerHeight), verticalAlignment = Alignment.CenterVertically) {
            Text(
                guideDayLabel(leftMs, nowMs),
                style = Antresol.typography.caption.copy(color = AntresolColors.Label),
                maxLines = 1,
                modifier = Modifier.width(ChannelColumnWidth).padding(start = 16.dp),
            )
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                Ruler(fromMs, toMs, nowMs, width)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize().testTag("guide-grid")) {
            items(rows, key = { it.id }) { row ->
                Row(Modifier.height(RowHeight)) {
                    GuideChannelCell(row, last = row.id == lastChannelId, onClick = { onWatchChannel(row.id) })
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                        Timeline(row, fromMs, toMs, nowMs, width) { program ->
                            onPick(GuidePick(row.id, row.number, row.name, row.favorite, program))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
            }
        }
    }
}

@Composable
private fun GuideChannelCell(row: ChannelEpgRow, last: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(ChannelColumnWidth)
            .fillMaxHeight()
            .focusClickable(AntresolShapes.smallCard, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                row.number.toString(),
                style = Antresol.typography.title.copy(
                    color = if (last) AntresolColors.Accent else AntresolColors.LabelSecondary,
                    fontFeatureSettings = "tnum",
                ),
            )
            if (row.favorite) FavoriteMark()
        }
        Text(row.name, style = Antresol.typography.caption.copy(color = AntresolColors.Label), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Half-hour ticks with their times, and the now mark. */
@Composable
private fun Ruler(fromMs: Long, toMs: Long, nowMs: Long, width: Dp) {
    val context = LocalContext.current
    Box(Modifier.width(width).fillMaxHeight()) {
        var tick = Epg.floorSlot(fromMs)
        if (tick < fromMs) tick += Epg.SLOT_MS
        while (tick < toMs) {
            val x = (tick - fromMs).minutesToDp()
            Box(Modifier.offset(x = x).align(Alignment.BottomStart).width(1.dp).height(8.dp).background(AntresolColors.LabelTertiary))
            Text(
                programClock(context, Instant.ofEpochMilli(tick).toString()),
                style = Antresol.typography.caption,
                modifier = Modifier.offset(x = x + 5.dp).align(Alignment.CenterStart),
            )
            tick += Epg.SLOT_MS
        }
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

@Composable
private fun Timeline(row: ChannelEpgRow, fromMs: Long, toMs: Long, nowMs: Long, width: Dp, onPick: (ChannelProgram) -> Unit) {
    val programs = remember(row.programs, fromMs, toMs) { Epg.visible(row, fromMs, toMs) }
    Box(Modifier.width(width).fillMaxHeight()) {
        programs.forEach { program ->
            val start = (Channels.epochMs(program.start) ?: return@forEach).coerceAtLeast(fromMs)
            val end = (Channels.epochMs(program.end) ?: return@forEach).coerceAtMost(toMs)
            GuideBlock(
                program = program,
                nowMs = nowMs,
                onClick = { onPick(program) },
                modifier = Modifier
                    .offset(x = (start - fromMs).minutesToDp())
                    .width((end - start).minutesToDp())
                    .fillMaxHeight()
                    .padding(start = 1.dp, end = 1.dp, top = 4.dp, bottom = 4.dp),
            )
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

@Composable
private fun GuideBlock(program: ChannelProgram, nowMs: Long, onClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val start = Channels.epochMs(program.start) ?: 0L
    val end = Channels.epochMs(program.end) ?: 0L
    val onAir = nowMs in start until end
    val past = end <= nowMs
    val fill = when {
        onAir -> AntresolColors.FillPrimary
        past -> AntresolColors.FillQuaternary
        else -> AntresolColors.FillTertiary
    }
    val text = if (past) AntresolColors.LabelTertiary else AntresolColors.Label
    val time = stringResource(R.string.tv_time, programClock(context, program.start), programClock(context, program.end))
    val description = listOf(Channels.programLine(program), time).joinToString(", ")
    Box(
        modifier
            .clip(AntresolShapes.badge)
            .background(fill)
            .focusClickable(AntresolShapes.badge, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                program.title,
                style = (if (onAir) Antresol.typography.bodyEmphasis else Antresol.typography.body).copy(color = text, fontSize = Antresol.typography.caption.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                programClock(context, program.start),
                style = Antresol.typography.caption.copy(color = if (past) AntresolColors.LabelTertiary else AntresolColors.LabelSecondary, fontSize = Antresol.typography.badge.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

