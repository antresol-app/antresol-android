package app.antresol.tv.ui.channels

import app.antresol.ui.channels.ChannelLogo
import app.antresol.ui.channels.channelNumberWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.tv.R as TvR
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.SegmentedPills
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.listRowColors
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsView
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.categoryKindTitle
import app.antresol.ui.channels.channelFilterTitle
import app.antresol.ui.channels.ProgrammeStill
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.programmeArt
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.playback.MenuOption
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.launch

/** Width of the channel list; the preview of the focused channel takes the rest. */
private val ListWidth = 430.dp

/** A row's channel number: the section-title size with tabular figures. */
private val ChannelNumberStyle: TextStyle
    @Composable get() = Antresol.typography.sectionTitle.copy(fontFeatureSettings = "tnum")

/**
 * The «ТВ» tab on the television, like Android TV Live Channels / TiviMate: the title with the view
 * switch «Каналы | Телегид» and the category button; then ONE numbered channel list with a preview
 * of the focused channel's programme (its still, time and progress, overview, what is next), or the
 * programme guide grid. Focus starts on the profile's last channel; OK tunes in; holding OK offers
 * the favourite. The player zaps inside the list it was opened from.
 *
 * Categories: a big list (an external playlist has dozens of groups) cannot be a row of pills, so
 * the category button («Все каналы · 842 ⌄») opens a list on the left (All, Favourites, then each
 * category with its channel count), which Left from the first column of the list or the guide opens
 * too. Choosing one narrows the list and the guide to it and hands focus to the channels.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel,
    onWatch: (Channel, ChannelFilter) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
    onPanelOpenChanged: (Boolean) -> Unit = {},
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.opened()
        viewModel.startRefreshing()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopRefreshing() }
    val nowMs = rememberNowMs()
    val scope = rememberCoroutineScope()
    val loaded = viewModel.channels
    val error = viewModel.error
    val filter = viewModel.filter
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    // Held OK opens the menu when it is released: a panel can't take focus while the key is down.
    var pendingMenu by remember { mutableStateOf<Channel?>(null) }
    var guidePanel by remember { mutableStateOf(false) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var menuSelected by remember { mutableStateOf(0) }
    // A panel covers the page: the shell hides its navigation meanwhile.
    LaunchedEffect(menuFor != null || guidePanel || categoriesOpen) { onPanelOpenChanged(menuFor != null || guidePanel || categoriesOpen) }

    val watch: (Channel) -> Unit = { onWatch(it, filter) }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val pending = pendingMenu
                val open = menuFor
                when {
                    pending != null && event.type == KeyEventType.KeyUp && event.key in OkKeys -> {
                        pendingMenu = null
                        menuSelected = 1
                        menuFor = pending
                        true
                    }
                    open != null -> handlePanelKey(event, 2, menuSelected, onSelect = { menuSelected = it }) { index ->
                        menuFor = null
                        if (index == 0) watch(open) else viewModel.toggleFavorite(open)
                    }
                    else -> false
                }
            },
    ) {
        // Switching «Каналы | Телегид» rebuilds the page under the pills: focus follows to the new ones.
        val viewFocus = remember { FocusRequester() }
        var refocusView by remember { mutableStateOf(false) }
        // The view just picked takes focus itself (its focused pill went away with the old view).
        val takeFocus: () -> Boolean = { refocusView || canTakeFocus() }
        LaunchedEffect(viewModel.view) {
            if (refocusView) {
                kotlinx.coroutines.delay(600)
                refocusView = false
            }
        }
        val categoryFocus = remember { FocusRequester() }
        // Back from the category list with nothing chosen returns to the button when it was opened from it;
        // a choice hands focus to the channels instead (the list takes it when its category changes).
        var categoriesFromButton by remember { mutableStateOf(false) }
        var filterWhenOpened by remember { mutableStateOf<ChannelFilter>(ChannelFilter.All) }
        // Counts the closings of the category list that leave the filter as it was: the list or the guide takes focus back.
        var refocus by remember { mutableIntStateOf(0) }
        // Left from the list or the guide.
        val openCategories = {
            categoriesFromButton = false
            filterWhenOpened = viewModel.filter
            categoriesOpen = true
        }
        val header: @Composable () -> Unit = {
            Header(
                viewModel,
                viewFocus,
                categoryFocus,
                onViewPicked = { refocusView = true },
                onCategories = {
                    categoriesFromButton = true
                    filterWhenOpened = viewModel.filter
                    categoriesOpen = true
                },
            )
        }
        when {
            error != null || loaded == null || loaded.isEmpty() ||
                (viewModel.shown.isEmpty() && filter == ChannelFilter.Favorites) -> Column(Modifier.fillMaxSize().padding(top = 84.dp)) {
                LaunchedEffect(Unit) { onScrolledToTop(true) }
                header()
                when {
                    error != null -> ErrorView(error, onRetry = viewModel::reload, modifier = Modifier.padding(top = 40.dp), takeFocus = canTakeFocus())
                    loaded == null -> LoadingView(Modifier.padding(top = 80.dp))
                    loaded.isEmpty() -> Empty(stringResource(R.string.tv_empty), stringResource(R.string.tv_empty_hint))
                    else -> Empty(stringResource(R.string.tv_favorites_empty), stringResource(R.string.tv_favorites_empty_hint))
                }
            }
            viewModel.view == ChannelsView.Guide -> {
                // The guide keeps its header and ruler under the navigation: never "scrolled".
                LaunchedEffect(Unit) { onScrolledToTop(true) }
                GuideScreen(viewModel, nowMs, takeFocus, header, onPanel = { guidePanel = it }, onWatch = watch, onCategories = openCategories, refocus = refocus)
            }
            else -> ChannelList(viewModel, nowMs, takeFocus, header, onScrolledToTop, onWatch = watch, onMenu = { pendingMenu = it }, onCategories = openCategories, refocus = refocus)
        }
        if (categoriesOpen) {
            CategoriesPanel(viewModel, onDismiss = {
                categoriesOpen = false
                if (viewModel.filter == filterWhenOpened) {
                    if (categoriesFromButton) scope.launch { categoryFocus.tryRequestFocus() } else refocus++
                }
                categoriesFromButton = false
            })
        }
        menuFor?.let { channel ->
            val favorite = viewModel.channels?.firstOrNull { it.id == channel.id }?.favorite ?: channel.favorite
            KeyPanel(
                title = stringResource(R.string.tv_channel_number, channel.number, channel.name),
                actions = listOf(
                    PanelAction(stringResource(R.string.tv_watch_now), AntresolIcons.Play),
                    PanelAction(
                        stringResource(if (favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
                        if (favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
                    ),
                ),
                selected = menuSelected,
            )
            androidx.activity.compose.BackHandler { menuFor = null }
        }
    }
}

/** «ТВ», «Каналы | Телегид» and the category button on one line. */
@Composable
private fun Header(
    viewModel: ChannelsViewModel,
    viewFocus: FocusRequester,
    categoryFocus: FocusRequester,
    onViewPicked: () -> Unit,
    onCategories: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPaddingH)
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.nav_tv), style = Antresol.typography.pageTitle)
        if (!viewModel.channels.isNullOrEmpty()) {
            Spacer(Modifier.width(24.dp))
            val viewLabel = stringResource(R.string.tv_view)
            SegmentedPills(
                options = listOf(stringResource(R.string.tv_view_channels), stringResource(R.string.tv_view_guide)),
                selectedIndex = viewModel.view.ordinal,
                onSelect = {
                    if (ChannelsView.entries[it] != viewModel.view) {
                        onViewPicked()
                        viewModel.showView(ChannelsView.entries[it])
                    }
                },
                modifier = Modifier
                    .semantics { contentDescription = viewLabel }
                    .focusRequester(viewFocus),
            )
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                CategoryButton(
                    label = channelFilterTitle(viewModel.filter, viewModel.serverCategories.orEmpty()),
                    count = viewModel.shown.size,
                    onClick = onCategories,
                    modifier = Modifier.focusRequester(categoryFocus),
                )
            }
        }
    }
}

/**
 * The current category and how many channels it has, with a chevron: OK opens the category list.
 * A button, not a row of pills, because there can be any number of categories.
 */
@Composable
private fun CategoryButton(label: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.tv_filter) + ": " + label + ", " + count
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .widthIn(max = 460.dp)
            .semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AntresolColors.Glass,
            contentColor = AntresolColors.Label,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.FOCUS_SCALE),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 14.dp).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(AntresolIcons.List, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(18.dp))
            Text(label, style = Antresol.typography.button, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(count.toString(), style = Antresol.typography.caption.copy(fontFeatureSettings = "tnum"), color = LocalContentColor.current.copy(alpha = 0.6f), maxLines = 1)
            Icon(AntresolIcons.ChevronDown, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The category list on the left: «Все каналы», «Избранное», then the categories as the server
 * orders them (its AI topics, the sources, the playlists' groups) each with its channel count; with
 * more than one kind they have headings. The current one is checked and has focus.
 */
@Composable
private fun CategoriesPanel(viewModel: ChannelsViewModel, onDismiss: () -> Unit) {
    val current = viewModel.filter
    val options = viewModel.filterOptions
    val headings = options.mapNotNull { it.kind }.distinct().size > 1
    var kindShown: String? = null
    OptionsPanel(
        title = stringResource(TvR.string.tv_categories),
        options = options.map { option ->
            val section = option.kind?.takeIf { headings && it != kindShown }?.let { categoryKindTitle(it) }
            if (option.kind != null) kindShown = option.kind
            val label = when (val filter = option.filter) {
                is ChannelFilter.Category -> option.label?.takeIf { it.isNotBlank() } ?: channelFilterTitle(filter)
                else -> channelFilterTitle(filter)
            }
            MenuOption(label, option.filter == current, badge = option.count.toString(), section = section) { viewModel.select(option.filter) }
        },
        onDismiss = onDismiss,
        width = 420.dp,
        fromStart = true,
    )
}

/**
 * The header and the channel list in one lazy column (so D-pad search moves between them, as on
 * the other pages), with the focused channel's programme drawn on the right.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelList(
    viewModel: ChannelsViewModel,
    nowMs: Long,
    canTakeFocus: () -> Boolean,
    header: @Composable () -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    onWatch: (Channel) -> Unit,
    onMenu: (Channel) -> Unit,
    onCategories: () -> Unit,
    refocus: Int,
) {
    val shown = viewModel.shown
    val numberWidth = channelNumberWidth(remember(shown) { shown.map { it.number } }, ChannelNumberStyle)
    val last = viewModel.lastChannel
    var focusedId by rememberSaveable(viewModel.filter.key) { mutableStateOf(last?.id ?: shown.firstOrNull()?.id) }
    // Where focus starts: the last channel (a TV turns on where it was left), else the first.
    val startIndex = shown.indexOfFirst { it.id == last?.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (startIndex > 3) startIndex - 1 else 0)
    val startFocus = remember(viewModel.filter.key) { FocusRequester() }
    // The first time focus comes into the list (from the navigation), it lands on the last channel.
    var entered by rememberSaveable(viewModel.filter.key) { mutableStateOf(false) }
    LaunchedEffect(viewModel.filter.key, shown.isNotEmpty()) {
        if (shown.isNotEmpty() && canTakeFocus()) entered = startFocus.tryRequestFocus()
    }
    val focused = shown.firstOrNull { it.id == focusedId } ?: shown.firstOrNull()
    val scope = rememberCoroutineScope()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    // Up/Down walk the list one channel at a time (scrolled first, so the row is there to focus).
    val rowFocus = remember { mutableMapOf<Long, FocusRequester>() }
    fun rowRequester(id: Long) = rowFocus.getOrPut(id) { FocusRequester() }
    // The category list was closed without a choice: back to the channel that had focus.
    LaunchedEffect(refocus) {
        if (refocus > 0) focused?.let { rowRequester(it.id).tryRequestFocus(8) }
    }
    var focusedIndex by remember { mutableStateOf(-1) }
    fun step(delta: Int): Boolean {
        if (focusedIndex < 0) return false
        val target = focusedIndex + delta
        if (target < 0) return false // Up from the first channel: the header.
        if (target >= shown.size) return true
        scope.launch {
            val lazyIndex = target + 1 // after the header item
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
            val viewportEnd = listState.layoutInfo.viewportEndOffset
            val topLimit = listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportSize.height / 6
            val fullyIn = info != null && info.offset >= topLimit && info.offset + info.size <= viewportEnd
            if (!fullyIn) listState.animateScrollToItem((lazyIndex - if (delta > 0) 4 else 1).coerceAtLeast(0))
            rowRequester(shown[target].id).tryRequestFocus(6)
        }
        return true
    }
    Box(Modifier.fillMaxSize()) {
        VerticalShelfScrolling {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 84.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> step(1)
                            Key.DirectionUp -> step(-1)
                            // The list is one column: Left from a channel opens the categories (as in TiviMate).
                            Key.DirectionLeft -> (focusedIndex >= 0).also { if (it) onCategories() }
                            else -> false
                        }
                    },
            ) {
                item(key = "header") { header() }
                itemsIndexed(shown, key = { _, channel -> channel.id }) { index, channel ->
                    TvChannelRow(
                        channel = channel,
                        numberWidth = numberWidth,
                        nowMs = nowMs,
                        last = channel.id == last?.id,
                        onClick = { onWatch(channel) },
                        onLongClick = { onMenu(channel) },
                        modifier = Modifier
                            .padding(start = ScreenPaddingH - 12.dp)
                            .width(ListWidth)
                            .then(if (index == startIndex) Modifier.focusRequester(startFocus) else Modifier)
                            .focusRequester(rowRequester(channel.id))
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    if (focusedIndex == index) focusedIndex = -1
                                    return@onFocusChanged
                                }
                                focusedIndex = index
                                focusedId = channel.id
                                if (!entered) {
                                    entered = true
                                    if (index != startIndex) scope.launch { startFocus.tryRequestFocus() }
                                }
                            },
                    )
                }
            }
        }
        if (focused != null) {
            ChannelPreview(
                focused,
                nowMs,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 84.dp + HeaderHeight, end = ScreenPaddingH)
                    .width(PreviewWidth),
            )
        }
    }
}

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** The header row's height with its bottom gap (the preview starts under it). */
private val HeaderHeight = 64.dp

/** The preview's width: what is right of the list. */
private val PreviewWidth = 960.dp - ScreenPaddingH * 2 - ListWidth - 28.dp

/** One row of the TV list: number, logo (or initials), name with the star, the programme and its progress, its still when it has one. */
@Composable
private fun TvChannelRow(channel: Channel, numberWidth: Dp, nowMs: Long, last: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    val program = Channels.showing(channel, nowMs)
    val description = listOfNotNull(
        stringResource(R.string.tv_channel_number, channel.number, channel.name),
        stringResource(R.string.tv_favorite).takeIf { channel.favorite },
        program?.let(Channels::programLine),
        stringResource(R.string.tv_last_channel).takeIf { last },
    ).joinToString(". ")
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.control),
        colors = listRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        val content = LocalContentColor.current
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                channel.number.toString(),
                style = ChannelNumberStyle,
                color = when {
                    hasFocus -> content
                    last -> AntresolColors.Accent
                    else -> AntresolColors.LabelSecondary
                },
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                // As wide as the list's widest number (four digits fit).
                modifier = Modifier.width(numberWidth),
            )
            // As the web's /tv rows: the logo, else the guide icon, else the channel's initials.
            ChannelLogo(urls?.image(channel.logoUrl), height = 26.dp, reserve = true, altUrl = urls?.image(channel.logoAlt), name = channel.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(channel.name, style = Antresol.typography.title, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (channel.favorite) FavoriteMark(tint = if (hasFocus) content else AntresolColors.Orange)
                }
                Text(
                    program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing),
                    style = Antresol.typography.caption,
                    color = content.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (program != null) {
                    // Times and «Далее» are in the preview on the right; the row stays a TV list row.
                    LiveProgressBar(Channels.progress(program, nowMs), content.copy(alpha = 0.8f), Modifier.padding(top = 4.dp))
                }
            }
            // The programme's picture only when it has one: no empty grey box.
            programmeArt(program)?.let { art ->
                ProgrammeStill(
                    url = urls?.image(art),
                    shape = AntresolShapes.badge,
                    modifier = Modifier.width(80.dp).aspectRatio(AntresolMotion.BACKDROP_ASPECT),
                )
            }
        }
    }
}

/** The focused channel's programme: the still, «N · name», title, time and progress, facts, overview, next. */
@Composable
private fun ChannelPreview(channel: Channel, nowMs: Long, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    val context = LocalContext.current
    val program = Channels.showing(channel, nowMs)
    val next = channel.next?.takeIf { it != program }
    // The key hints are measured first and always get their full height; the programme above takes
    // what is left (the overview shortens), so a long text never squeezes the «OK» chips.
    Column(modifier.height(540.dp - 84.dp - HeaderHeight)) {
        Column(Modifier.weight(1f).clipToBounds(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // At most 280 dp wide, so the programme's text and «Далее» fit under it on the 540 dp screen.
            ProgrammeStill(
                url = urls?.image(programmeArt(program)),
                shape = AntresolShapes.card,
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(AntresolMotion.BACKDROP_ASPECT),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                ChannelLogo(urls?.image(channel.logoUrl), height = 24.dp, altUrl = urls?.image(channel.logoAlt), name = channel.name)
                Text(
                    stringResource(R.string.tv_channel_number, channel.number, channel.name),
                    style = Antresol.typography.caption.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
                if (channel.favorite) FavoriteMark()
            }
            Text(program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing), style = Antresol.typography.sectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (program != null) {
                ProgramTime(program, nowMs)
                programFacts(program).takeIf { it.isNotEmpty() }?.let { Text(it, style = Antresol.typography.caption) }
                if (program.overview.isNotBlank()) {
                    // Takes what is left after everything else: two lines, one, or none (Compose
                    // does not shorten text to a height, so the line count steps down instead).
                    var lines by remember(program.id, program.overview) { mutableIntStateOf(2) }
                    if (lines > 0) {
                        Text(
                            program.overview,
                            style = Antresol.typography.body,
                            color = AntresolColors.LabelSecondary,
                            maxLines = lines,
                            overflow = TextOverflow.Ellipsis,
                            // One step per layout made with the current count (a layout can be reported twice).
                            onTextLayout = { if (it.hasVisualOverflow && it.layoutInput.maxLines == lines && lines > 0) lines-- },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            if (next != null) {
                Text(
                    stringResource(R.string.tv_next_short, programClock(context, next.start), Channels.programLine(next)),
                    style = Antresol.typography.caption,
                    color = AntresolColors.LabelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)) {
            KeyHint("OK", stringResource(R.string.tv_watch_now))
            KeyHint("OK ⋯", stringResource(if (channel.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add))
        }
    }
}

@Composable
private fun Empty(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
        Text(hint, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
    }
}

