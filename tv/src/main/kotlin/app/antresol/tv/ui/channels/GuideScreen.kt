package app.antresol.tv.ui.channels

import app.antresol.ui.channels.ChannelLogo
import app.antresol.ui.channels.LocalMinuteWidth
import app.antresol.ui.channels.minutesToDp
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.Density
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
import app.antresol.tv.ui.components.FocusRequesters
import app.antresol.tv.ui.components.focusRequester
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.ProgrammeStill
import app.antresol.ui.channels.guideDayLabel
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.programmeArt
import app.antresol.ui.channels.stickyLabel
import app.antresol.ui.channels.timeFormat
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.launch

private val ChannelColumnWidth = 168.dp
private val RowHeight = 52.dp
private val RulerHeight = 28.dp

/** Lazy items before the first channel row: the page header and the sticky ruler. */
private const val FIRST_ROW = 2

/** What tells a channel's programmes apart (the merge's key). */
private val ChannelProgram.key: String get() = Epg.key(this)

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val minute = remember(rows.isNotEmpty()) { Epg.minuteWidth(rows, 170f, 5f, 28f).dp }
    val tick = Epg.tickMs(minute.value, 110f)
    val width = (toMs - fromMs).minutesToDp(minute)
    // Only the blocks on screen keep one: the guide is paged hours ahead.
    val requesters = remember { FocusRequesters<String>() }
    // Keyed by the channel too: programmes without an id that start together on two channels are different blocks.
    fun requesterKey(row: ChannelEpgRow, program: ChannelProgram) = "${row.id}/${program.key}"
    fun requester(row: ChannelEpgRow, program: ChannelProgram) = requesters[requesterKey(row, program)]
    // The time position kept by vertical moves, and where focus is.
    var anchorMs by remember { mutableLongStateOf(nowMs) }
    var focusedRow by remember { mutableStateOf(rows.indexOfFirst { it.id == lastChannelId }.coerceAtLeast(0)) }
    var vertical by remember { mutableStateOf(false) }
    // Focus is on a programme block (Up/Down are ours), not on the header.
    var inGrid by remember { mutableStateOf(false) }
    // The time at the visible left edge. Read in layout, callbacks and derived state only, never in
    // this composition: the grid must not recompose with every frame of a scroll.
    val visibleFromMs = remember(fromMs, minute, density) {
        { fromMs + with(density) { (scroll.value.toDp() / minute * 60_000f).toLong() } }
    }
    // The ruler's day changes on a quarter of an hour at the earliest (time zones), not with every pixel.
    val dayAtMs by remember(visibleFromMs) { derivedStateOf { Epg.floorTo(visibleFromMs(), Epg.SLOT_MS / 2) } }
    // Only the programmes around the view are composed (a guide paged two days ahead has thousands).
    val composed by remember(visibleFromMs, minute, density) {
        derivedStateOf {
            val viewMs = with(density) { (scroll.viewportSize.toDp() / minute * 60_000f).toLong() }
            Epg.composedWindow(visibleFromMs(), viewMs)
        }
    }
    // A programme outside it has no block to focus yet: it is scrolled to first (as focusing it would do).
    fun isComposed(program: ChannelProgram): Boolean = Epg.overlapping(listOf(program), composed.first, composed.last).isNotEmpty()
    val clock = remember(context) { timeFormat(context) }
    val reserveLogo = remember(rows) { reserveLogoSlot(rows.map { it.logoUrl }) }

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
            if (!isComposed(target)) reveal(target, fromMs, scroll, density, minute)
            requester(rows[start], target).tryRequestFocus(8)
        }
    }

    var focusedProgram by remember { mutableStateOf<ChannelProgram?>(null) }

    /** Left/Right: the previous/next programme of the channel, revealed first; stays at the ends. */
    fun moveHorizontally(step: Int): Boolean {
        if (!inGrid) return false
        val row = rows.getOrNull(focusedRow) ?: return true
        val programs = Epg.visible(row, fromMs, toMs)
        val index = focusedProgram?.key.let { key -> programs.indexOfFirst { it.key == key } }
        val target = programs.getOrNull(index + step)
        if (index < 0 || target == null) {
            if (step > 0) onNearEnd()
            return true
        }
        scope.launch {
            reveal(target, fromMs, scroll, density, minute)
            requester(row, target).tryRequestFocus(6)
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
            if (!isComposed(program)) reveal(program, fromMs, scroll, density, minute)
            requester(rows[target], program).tryRequestFocus(6)
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
                            guideDayLabel(dayAtMs, nowMs),
                            style = Antresol.typography.caption,
                            color = AntresolColors.Label,
                            modifier = Modifier.width(ChannelColumnWidth),
                        )
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll, enabled = false)) {
                            Ruler(fromMs, toMs, composed, nowMs, width, tick, clock)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
                }
            }
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                Row(Modifier.height(RowHeight).padding(start = ScreenPaddingH)) {
                    ChannelCell(row, reserveLogo = reserveLogo, last = row.id == lastChannelId, focused = index == focusedRow)
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
                            composed = composed,
                            nowMs = nowMs,
                            width = width,
                            clock = clock,
                            requesters = requesters,
                            requesterKey = ::requesterKey,
                            onFocused = { program ->
                                focusedRow = index
                                focusedProgram = program
                                if (vertical) {
                                    vertical = false
                                } else {
                                    anchorMs = Epg.anchorFor(program, visibleFromMs())
                                }
                                scope.launch { reveal(program, fromMs, scroll, density, minute) }
                                if (toMs - (program.endMs ?: 0L) < 2 * Epg.HOUR_MS) onNearEnd()
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
    val start = program.startMs ?: return
    val end = program.endMs ?: return
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
            maxLines = 1,
            softWrap = false,
            // Three digits: external channels are numbered from 100.
            modifier = Modifier.width(34.dp),
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

/** The ticks with their times (those around the view, see [Epg.composedWindow]) and the now mark. */
@Composable
private fun Ruler(fromMs: Long, toMs: Long, composed: LongRange, nowMs: Long, width: Dp, stepMs: Long, clock: java.text.DateFormat) {
    Box(Modifier.width(width).fillMaxHeight()) {
        RulerTicks(fromMs, minOf(toMs, composed.last), maxOf(fromMs, composed.first), stepMs, clock)
        if (nowMs in fromMs until toMs) {
            Box(Modifier.offset(x = (nowMs - fromMs).minutesToDp() - 1.dp).align(Alignment.BottomStart).width(2.dp).height(12.dp).background(AntresolColors.Red))
        }
    }
}

/** The ticks from [firstMs] to [toMs]; apart from the now mark, so the clock's tick does not redraw them. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RulerTicks(fromMs: Long, toMs: Long, firstMs: Long, stepMs: Long, clock: java.text.DateFormat) {
    var tick = Epg.floorTo(firstMs, stepMs)
    if (tick < firstMs) tick += stepMs
    while (tick < toMs) {
        val x = (tick - fromMs).minutesToDp()
        Box(Modifier.offset(x = x).align(Alignment.BottomStart).width(1.dp).height(7.dp).background(AntresolColors.LabelTertiary))
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
    leftMs: () -> Long,
    row: ChannelEpgRow,
    fromMs: Long,
    toMs: Long,
    composed: LongRange,
    nowMs: Long,
    width: Dp,
    clock: java.text.DateFormat,
    requesters: FocusRequesters<String>,
    requesterKey: (ChannelEpgRow, ChannelProgram) -> String,
    onFocused: (ChannelProgram) -> Unit,
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
            // Keyed: a block keeps its state (and the focus) when the composed window moves along the row.
            key(program.key) {
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
                        .padding(horizontal = 1.dp, vertical = 3.dp)
                        .focusRequester(requesters, requesterKey(row, program))
                        .onFocusChanged { if (it.isFocused) onFocused(program) },
                )
            }
        }
        if (nowMs in fromMs until toMs) {
            Box(Modifier.offset(x = (nowMs - fromMs).minutesToDp() - 1.dp).width(2.dp).fillMaxHeight().background(AntresolColors.Red))
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
    val startClock = remember(program, clock) { program.startMs?.let { programClock(clock, it) }.orEmpty() }
    val endClock = remember(program, clock) { program.endMs?.let { programClock(clock, it) }.orEmpty() }
    val time = stringResource(R.string.tv_time, startClock, endClock)
    val description = listOf(Channels.programLine(program), time).joinToString(", ")
    val minute = LocalMinuteWidth.current
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
        Column(
            Modifier
                .stickyLabel(
                    blockWidth = { (endMs - startMs).minutesToDp(minute).toPx() },
                    inset = { Epg.stickyLabelOffsetMs(startMs, endMs, leftMs()).minutesToDp(minute).toPx() },
                )
                .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                program.title,
                style = if (onAir) Antresol.typography.bodyEmphasis.copy(fontSize = Antresol.typography.caption.fontSize) else Antresol.typography.caption,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                startClock,
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
        PanelAction(stringResource(R.string.tv_watch_now), AntresolIcons.Play).takeIf { onAir },
        PanelAction(
            stringResource(if (row.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
            if (row.favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
        ),
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
