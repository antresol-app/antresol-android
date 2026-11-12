package app.antresol.tv.ui.channels

import app.antresol.ui.channels.ChannelLogo
import app.antresol.ui.channels.reserveLogoSlot
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.channels.Channels
import app.antresol.core.channels.Epg
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelProgram
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.ProgrammeStill
import app.antresol.ui.channels.guideDayLabel
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.programmeArt
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import java.time.Instant
import kotlinx.coroutines.launch

private val ChannelColumnWidth = 168.dp
private val RowHeight = 52.dp
private val RulerHeight = 28.dp

/** Lazy items before the first channel row: the page header and the sticky ruler. */
private const val FIRST_ROW = 2

/** Width of one minute, set per guide ([Epg.minuteWidth]): a 90-min film is 450 dp, short clips are stretched. */
private val LocalMinuteWidth = androidx.compose.runtime.staticCompositionLocalOf { 5.dp }

@Composable
private fun Long.minutesToDp(): Dp = LocalMinuteWidth.current * (this / 60_000f)

private fun Long.minutesToDp(minute: Dp): Dp = minute * (this / 60_000f)

/** A programme picked in the guide with its channel row. */
private data class Picked(val row: ChannelEpgRow, val program: ChannelProgram)

/**
 * «Телегид» on the television: a sticky channel column (number, name, star), the time ruler with
 * half-hour ticks, programme blocks as wide as they are long, the now line; past programmes dimmed,
 * the one on air highlighted. A focusable grid: Left/Right walk the programmes of a channel, Up/Down
 * go to the programme of the channel above/below at the SAME time position (the anchor stays while
 * moving vertically). OK opens the details (Watch while on air, the channel's favourite). The next
 * window is loaded as focus nears the loaded end.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen(viewModel: ChannelsViewModel, nowMs: Long, canTakeFocus: () -> Boolean, header: @Composable () -> Unit, onPanel: (Boolean) -> Unit, onWatch: (Channel) -> Unit) {
    val rows = viewModel.guideRows
    val error = viewModel.guideError
    LaunchedEffect(Unit) { if (rows == null && error == null) viewModel.loadGuide() }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var selected by remember { mutableStateOf(0) }
    LaunchedEffect(picked != null) { onPanel(picked != null) }
    val pick = picked
    val channel = pick?.let { p -> viewModel.channels?.firstOrNull { it.id == p.row.id } }
    val onAir = pick != null && Channels.isOnAir(pick.program, nowMs)
    val favorite = channel?.favorite ?: pick?.row?.favorite ?: false
    val run: (Int) -> Unit = { index ->
        val watchIndex = if (onAir) 0 else -1
        if (index == watchIndex) {
            picked = null
            channel?.let(onWatch)
        } else {
            channel?.let(viewModel::toggleFavorite)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (picked == null) return@onPreviewKeyEvent false
                handlePanelKey(event, if (onAir) 2 else 1, selected, onSelect = { selected = it }, onRun = run)
            },
    ) {
        when {
            error != null || rows == null -> Column(Modifier.fillMaxSize().padding(top = 84.dp)) {
                header()
                if (error != null) {
                    ErrorView(error, onRetry = viewModel::loadGuide, modifier = Modifier.padding(top = 40.dp), takeFocus = canTakeFocus())
                } else {
                    LoadingView(Modifier.padding(top = 80.dp))
                }
            }
            else -> GuideGrid(
                header = header,
                rows = viewModel.shownGuide,
                fromMs = viewModel.guideFromMs,
                toMs = viewModel.guideToMs,
                nowMs = nowMs,
                lastChannelId = viewModel.lastChannel?.id,
                canTakeFocus = { canTakeFocus() && picked == null },
                onPick = { row, program ->
                    selected = 0
                    picked = Picked(row, program)
                },
                onNearEnd = viewModel::loadMoreGuide,
            )
        }
        if (pick != null) {
            ProgramPanel(
                row = pick.row.copy(favorite = favorite),
                program = pick.program,
                nowMs = nowMs,
                onAir = onAir,
                selected = selected,
            )
            BackHandler { picked = null }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuideGrid(
    header: @Composable () -> Unit,
    rows: List<ChannelEpgRow>,
    fromMs: Long,
    toMs: Long,
    nowMs: Long,
    lastChannelId: Long?,
    canTakeFocus: () -> Boolean,
    onPick: (ChannelEpgRow, ChannelProgram) -> Unit,
    onNearEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val minute = remember(rows.isNotEmpty()) { Epg.minuteWidth(rows, 170f, 5f, 28f).dp }
    val tick = Epg.tickMs(minute.value, 110f)
    val width = (toMs - fromMs).minutesToDp(minute)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(program: ChannelProgram) = requesters.getOrPut(program.id.ifEmpty { program.start }) { FocusRequester() }
    // The time position kept by vertical moves, and where focus is.
    var anchorMs by remember { mutableLongStateOf(nowMs) }
    var focusedRow by remember { mutableStateOf(rows.indexOfFirst { it.id == lastChannelId }.coerceAtLeast(0)) }
    var vertical by remember { mutableStateOf(false) }
    // Focus is on a programme block (Up/Down are ours), not on the header.
    var inGrid by remember { mutableStateOf(false) }
    val visibleFromMs = fromMs + with(density) { (scroll.value.toDp() / minute * 60_000f).toLong() }

    LaunchedEffect(fromMs, rows.isNotEmpty()) {
        if (rows.isEmpty()) return@LaunchedEffect
        // Open on now (half an hour of the past in view), focus on the last channel's programme on air.
        scroll.scrollTo(with(density) { (nowMs - 2 * tick - fromMs).coerceAtLeast(0).minutesToDp(minute).roundToPx() })
        val start = rows.indexOfFirst { it.id == lastChannelId }.coerceAtLeast(0)
        listState.scrollToItem(if (start > 3) start - 1 + FIRST_ROW else 0)
        anchorMs = nowMs
        val target = Epg.landing(Epg.visible(rows[start], fromMs, toMs), nowMs) ?: return@LaunchedEffect
        if (canTakeFocus()) {
            vertical = true
            requester(target).tryRequestFocus(8)
        }
    }

    var focusedProgram by remember { mutableStateOf<ChannelProgram?>(null) }

    /** Left/Right: the previous/next programme of the channel, revealed first; stays at the ends. */
    fun moveHorizontally(step: Int): Boolean {
        if (!inGrid) return false
        val row = rows.getOrNull(focusedRow) ?: return true
        val programs = Epg.visible(row, fromMs, toMs)
        val index = programs.indexOfFirst { it.id == focusedProgram?.id }
        val target = programs.getOrNull(index + step)
        if (index < 0 || target == null) {
            if (step > 0) onNearEnd()
            return true
        }
        scope.launch {
            reveal(target, fromMs, scroll, density, minute)
            requester(target).tryRequestFocus(6)
        }
        return true
    }

    fun moveVertically(step: Int): Boolean {
        if (!inGrid) return false
        val target = focusedRow + step
        if (target !in rows.indices) return step > 0 // Down at the end stays; Up leaves for the header.
        val program = Epg.landing(Epg.visible(rows[target], fromMs, toMs), anchorMs) ?: return true
        scope.launch {
            val lazyIndex = target + FIRST_ROW
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }.filter { it >= FIRST_ROW }
            if (lazyIndex !in visible.drop(1).dropLast(1)) {
                listState.scrollToItem(if (step > 0) (lazyIndex - 3).coerceAtLeast(FIRST_ROW) else (lazyIndex - 1).coerceAtLeast(0))
            }
            vertical = true
            requester(program).tryRequestFocus(6)
        }
        return true
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalMinuteWidth provides minute) {
    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> moveVertically(1)
                    Key.DirectionUp -> moveVertically(-1)
                    Key.DirectionLeft -> moveHorizontally(-1)
                    Key.DirectionRight -> moveHorizontally(1)
                    else -> false
                }
            },
    ) {
        LazyColumn(
            state = listState,
            // Padded as a whole (not its content): the sticky ruler stops under the navigation.
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().padding(top = 84.dp),
        ) {
            item(key = "header") { header() }
            stickyHeader(key = "ruler") {
                Column(Modifier.fillMaxWidth().background(AntresolColors.Background)) {
                    Row(Modifier.height(RulerHeight).padding(start = ScreenPaddingH), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            guideDayLabel(visibleFromMs, nowMs),
                            style = Antresol.typography.caption,
                            color = AntresolColors.Label,
                            modifier = Modifier.width(ChannelColumnWidth),
                        )
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll, enabled = false)) {
                            Ruler(fromMs, toMs, nowMs, width, tick)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
                }
            }
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                Row(Modifier.height(RowHeight).padding(start = ScreenPaddingH)) {
                    ChannelCell(row, reserveLogo = reserveLogoSlot(rows.map { it.logoUrl }), last = row.id == lastChannelId, focused = index == focusedRow)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(scroll, enabled = false)
                            .onFocusChanged { if (it.hasFocus) inGrid = true else if (index == focusedRow) inGrid = false }
                            .focusGroup(),
                    ) {
                        Timeline(
                            leftMs = visibleFromMs,
                            row = row,
                            fromMs = fromMs,
                            toMs = toMs,
                            nowMs = nowMs,
                            width = width,
                            requester = ::requester,
                            onFocused = { program ->
                                focusedRow = index
                                focusedProgram = program
                                if (vertical) {
                                    vertical = false
                                } else {
                                    anchorMs = Epg.anchorFor(program, visibleFromMs)
                                }
                                scope.launch { reveal(program, fromMs, scroll, density, minute) }
                                val end = Channels.epochMs(program.end) ?: 0L
                                if (toMs - end < 2 * Epg.HOUR_MS) onNearEnd()
                            },
                            onPick = { program -> onPick(row, program) },
                        )
                    }
                }
                Box(Modifier.padding(start = ScreenPaddingH).fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
            }
        }
    }
    }
}

/** Scrolls the shared timeline so the focused programme's start (or the anchor) is in view. */
private suspend fun reveal(program: ChannelProgram, fromMs: Long, scroll: ScrollState, density: androidx.compose.ui.unit.Density, minute: Dp) {
    val start = Channels.epochMs(program.start) ?: return
    val end = Channels.epochMs(program.end) ?: return
    with(density) {
        val left = (start - fromMs).coerceAtLeast(0).minutesToDp(minute).roundToPx()
        val right = (end - fromMs).minutesToDp(minute).roundToPx()
        val margin = 24.dp.roundToPx()
        val viewport = scroll.viewportSize
        when {
            // A programme that began off-screen to the left but still runs into view stays as it is.
            left < scroll.value -> if (right - 4 * margin > scroll.value) Unit else scroll.animateScrollTo((left - margin).coerceAtLeast(0))
            // Paging forward keeps the focused start a quarter in, so what came before stays in view.
            left > scroll.value + viewport - 4 * margin -> scroll.animateScrollTo((left - viewport / 4).coerceIn(0, scroll.maxValue))
        }
    }
}

@Composable
private fun ChannelCell(row: ChannelEpgRow, reserveLogo: Boolean, last: Boolean, focused: Boolean) {
    val urls = app.antresol.tv.ui.LocalMediaUrls.current
    Row(
        Modifier
            .width(ChannelColumnWidth)
            .fillMaxHeight()
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            row.number.toString(),
            style = Antresol.typography.title.copy(fontFeatureSettings = "tnum"),
            color = when {
                last -> AntresolColors.Accent
                focused -> AntresolColors.Label
                else -> AntresolColors.LabelSecondary
            },
            modifier = Modifier.width(26.dp),
        )
        ChannelLogo(urls?.image(row.logoUrl), height = 22.dp, reserve = reserveLogo)
        Text(
            row.name,
            style = Antresol.typography.caption.copy(hyphens = androidx.compose.ui.text.style.Hyphens.None),
            color = if (focused) AntresolColors.Label else AntresolColors.LabelSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.favorite) FavoriteMark()
    }
}

@Composable
private fun Ruler(fromMs: Long, toMs: Long, nowMs: Long, width: Dp, stepMs: Long) {
    val context = LocalContext.current
    Box(Modifier.width(width).fillMaxHeight()) {
        var tick = Epg.floorTo(fromMs, stepMs)
        if (tick < fromMs) tick += stepMs
        while (tick < toMs) {
            val x = (tick - fromMs).minutesToDp()
            Box(Modifier.offset(x = x).align(Alignment.BottomStart).width(1.dp).height(7.dp).background(AntresolColors.LabelTertiary))
            Text(
                programClock(context, Instant.ofEpochMilli(tick).toString()),
                style = Antresol.typography.caption,
                modifier = Modifier.offset(x = x + 5.dp).align(Alignment.CenterStart),
            )
            tick += stepMs
        }
        if (nowMs in fromMs until toMs) {
            Box(Modifier.offset(x = (nowMs - fromMs).minutesToDp() - 1.dp).align(Alignment.BottomStart).width(2.dp).height(12.dp).background(AntresolColors.Red))
        }
    }
}

@Composable
private fun Timeline(
    leftMs: Long,
    row: ChannelEpgRow,
    fromMs: Long,
    toMs: Long,
    nowMs: Long,
    width: Dp,
    requester: (ChannelProgram) -> FocusRequester,
    onFocused: (ChannelProgram) -> Unit,
    onPick: (ChannelProgram) -> Unit,
) {
    val programs = remember(row.programs, fromMs, toMs) { Epg.visible(row, fromMs, toMs) }
    Box(Modifier.width(width).fillMaxHeight()) {
        programs.forEach { program ->
            val start = (Channels.epochMs(program.start) ?: return@forEach).coerceAtLeast(fromMs)
            val end = (Channels.epochMs(program.end) ?: return@forEach).coerceAtMost(toMs)
            val inset = stickyInset(start, end, leftMs)
            GuideBlock(
                program = program,
                nowMs = nowMs,
                textInset = inset,
                // A sliver at the visible edge shows no text rather than «…» over a clipped time.
                showText = (end - start).minutesToDp() - inset >= MinTextWidth,
                onClick = { onPick(program) },
                modifier = Modifier
                    .offset(x = (start - fromMs).minutesToDp())
                    .width((end - start).minutesToDp())
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp, vertical = 3.dp)
                    .focusRequester(requester(program))
                    .onFocusChanged { if (it.isFocused) onFocused(program) },
            )
        }
        if (nowMs in fromMs until toMs) {
            Box(Modifier.offset(x = (nowMs - fromMs).minutesToDp() - 1.dp).width(2.dp).fillMaxHeight().background(AntresolColors.Red))
        }
    }
}

@Composable
private fun GuideBlock(program: ChannelProgram, nowMs: Long, textInset: Dp, showText: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val start = Channels.epochMs(program.start) ?: 0L
    val end = Channels.epochMs(program.end) ?: 0L
    val onAir = nowMs in start until end
    val past = end <= nowMs
    val time = stringResource(R.string.tv_time, programClock(context, program.start), programClock(context, program.end))
    val description = listOf(Channels.programLine(program), time).joinToString(", ")
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.badge),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                onAir -> AntresolColors.FillPrimary
                past -> AntresolColors.FillQuaternary
                else -> AntresolColors.FillTertiary
            },
            contentColor = if (past) AntresolColors.LabelTertiary else AntresolColors.Label,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        val content = LocalContentColor.current
        if (showText) Column(Modifier.padding(start = 8.dp + textInset, end = 8.dp, top = 5.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                program.title,
                style = if (onAir) Antresol.typography.bodyEmphasis.copy(fontSize = Antresol.typography.caption.fontSize) else Antresol.typography.caption,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                programClock(context, program.start),
                style = Antresol.typography.badge,
                color = content.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The details of a programme over the guide: still, channel, title, time, facts, overview, actions. */
@Composable
private fun ProgramPanel(row: ChannelEpgRow, program: ChannelProgram, nowMs: Long, onAir: Boolean, selected: Int) {
    val urls = LocalMediaUrls.current
    val actions = listOfNotNull(
        PanelAction(stringResource(R.string.tv_watch_now), AntresolIcons.Play) {}.takeIf { onAir },
        PanelAction(
            stringResource(if (row.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
            if (row.favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
        ) {},
    )
    KeyPanel(title = stringResource(R.string.tv_channel_number, row.number, row.name), actions = actions, selected = selected, width = 420.dp) {
        ProgrammeStill(
            url = urls?.image(programmeArt(program)),
            shape = AntresolShapes.card,
            modifier = Modifier.fillMaxWidth().aspectRatio(AntresolMotion.BACKDROP_ASPECT),
        )
        Text(program.title, style = Antresol.typography.sectionTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (program.subtitle.isNotBlank()) Text(program.subtitle, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, maxLines = 1)
        ProgramTime(program, nowMs)
        programFacts(program).takeIf { it.isNotEmpty() }?.let { Text(it, style = Antresol.typography.caption) }
        if (program.overview.isNotBlank()) {
            Text(program.overview, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * How far a block's title moves right so it stays at the visible left edge when the programme
 * began off-screen; the same inset in every row (a short remainder shortens the title instead).
 */
/** Narrowest visible part of a block that still gets its title and time. */
private val MinTextWidth = 48.dp

@Composable
private fun stickyInset(startMs: Long, endMs: Long, leftMs: Long): Dp =
    Epg.stickyLabelOffsetMs(startMs, endMs, leftMs).minutesToDp()
