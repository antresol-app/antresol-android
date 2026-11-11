package app.antresol.tv.ui.channels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MenuOption
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.SegmentedPills
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.listRowColors
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsView
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.ProgrammeStill
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.programmeArt
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** Width of the channel list; the preview of the focused channel takes the rest. */
private val ListWidth = 430.dp

/**
 * The «ТВ» tab on the television, like Android TV Live Channels / TiviMate: the title with the view
 * switch «Каналы | Телегид» and the filters «Все · Избранное · <themes>»; then ONE numbered channel
 * list with a preview of the focused channel's programme (its still, time and progress, overview,
 * what is next), or the programme guide grid. Focus starts on the profile's last channel; OK tunes
 * in; holding OK offers the favourite. The player zaps inside the list it was opened from.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel,
    onWatch: (Channel, ChannelFilter) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.startRefreshing() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopRefreshing() }
    LaunchedEffect(Unit) { onScrolledToTop(true) }
    val nowMs = rememberNowMs()
    val loaded = viewModel.channels
    val error = viewModel.error
    val filter = viewModel.filter
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    val watch: (Channel) -> Unit = { onWatch(it, filter) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 84.dp)) {
            Header(viewModel)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null -> ErrorView(error, onRetry = viewModel::reload, modifier = Modifier.padding(top = 40.dp), takeFocus = canTakeFocus())
                    loaded == null -> LoadingView(Modifier.padding(top = 80.dp))
                    loaded.isEmpty() -> Empty(stringResource(R.string.tv_empty), stringResource(R.string.tv_empty_hint))
                    viewModel.shown.isEmpty() && filter == ChannelFilter.Favorites ->
                        Empty(stringResource(R.string.tv_favorites_empty), stringResource(R.string.tv_favorites_empty_hint))
                    viewModel.view == ChannelsView.Guide -> GuideScreen(viewModel, nowMs, canTakeFocus, onWatch = watch)
                    else -> ChannelList(viewModel, nowMs, canTakeFocus, onWatch = watch, onMenu = { menuFor = it })
                }
            }
        }
        menuFor?.let { channel ->
            val favorite = viewModel.channels?.firstOrNull { it.id == channel.id }?.favorite ?: channel.favorite
            OptionsPanel(
                title = stringResource(R.string.tv_channel_number, channel.number, channel.name),
                options = listOf(
                    MenuOption(stringResource(R.string.tv_watch_now), false) { watch(channel) },
                    MenuOption(stringResource(if (favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add), false) {
                        viewModel.toggleFavorite(channel)
                    },
                ),
                onDismiss = { menuFor = null },
                focusIndex = 1,
            )
            androidx.activity.compose.BackHandler { menuFor = null }
        }
    }
}

/** «ТВ», «Каналы | Телегид» and the filters on one line. */
@Composable
private fun Header(viewModel: ChannelsViewModel) {
    val themes = viewModel.themes
    val filters = listOf(ChannelFilter.All, ChannelFilter.Favorites) + themes.map { ChannelFilter.Theme(it) }
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
                onSelect = { viewModel.showView(ChannelsView.entries[it]) },
                modifier = Modifier.semantics { contentDescription = viewLabel },
            )
            Spacer(Modifier.width(16.dp))
            val filterLabel = stringResource(R.string.tv_filter)
            val labels = filters.map { option ->
                when (option) {
                    ChannelFilter.All -> stringResource(R.string.tv_all_sections)
                    ChannelFilter.Favorites -> stringResource(R.string.tv_favorites)
                    is ChannelFilter.Theme -> option.category
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                SegmentedPills(
                    options = labels,
                    selectedIndex = filters.indexOf(viewModel.filter).coerceAtLeast(0),
                    onSelect = { viewModel.select(filters[it]) },
                    modifier = Modifier
                        .semantics { contentDescription = filterLabel }
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** The channel list on the left, the focused channel's programme on the right. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelList(
    viewModel: ChannelsViewModel,
    nowMs: Long,
    canTakeFocus: () -> Boolean,
    onWatch: (Channel) -> Unit,
    onMenu: (Channel) -> Unit,
) {
    val shown = viewModel.shown
    val last = viewModel.lastChannel
    var focusedId by rememberSaveable(viewModel.filter.key) { mutableStateOf(last?.id ?: shown.firstOrNull()?.id) }
    val startIndex = shown.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 2).coerceAtLeast(0))
    val startFocus = remember(viewModel.filter.key) { FocusRequester() }
    LaunchedEffect(viewModel.filter.key, shown.isNotEmpty()) {
        if (shown.isNotEmpty() && canTakeFocus()) startFocus.tryRequestFocus()
    }
    val focused = shown.firstOrNull { it.id == focusedId } ?: shown.firstOrNull()
    Row(Modifier.fillMaxSize()) {
        VerticalShelfScrolling(leadingFraction = 0.2f, trailingFraction = 0.2f) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = ScreenPaddingH - 12.dp, end = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .width(ListWidth + ScreenPaddingH)
                    .fillMaxHeight()
                    .focusRestorer { startFocus },
            ) {
                itemsIndexed(shown, key = { _, channel -> channel.id }) { index, channel ->
                    TvChannelRow(
                        channel = channel,
                        nowMs = nowMs,
                        last = channel.id == last?.id,
                        onClick = { onWatch(channel) },
                        onLongClick = { onMenu(channel) },
                        modifier = Modifier
                            .then(if (index == startIndex) Modifier.focusRequester(startFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedId = channel.id },
                    )
                }
            }
        }
        if (focused != null) ChannelPreview(focused, nowMs, Modifier.weight(1f).padding(start = 28.dp, end = ScreenPaddingH))
    }
}

/** One row of the TV list: number, a small still, name with the star, the programme and its progress. */
@Composable
private fun TvChannelRow(channel: Channel, nowMs: Long, last: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    val context = LocalContext.current
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
                style = Antresol.typography.sectionTitle.copy(fontFeatureSettings = "tnum"),
                color = when {
                    hasFocus -> content
                    last -> AntresolColors.Accent
                    else -> AntresolColors.LabelSecondary
                },
                textAlign = TextAlign.End,
                modifier = Modifier.width(30.dp),
            )
            ProgrammeStill(
                url = urls?.image(programmeArt(program)),
                shape = AntresolShapes.badge,
                modifier = Modifier.width(80.dp).aspectRatio(AntresolMotion.BACKDROP_ASPECT),
            )
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 3.dp)) {
                        LiveProgressBar(Channels.progress(program, nowMs), content, Modifier.weight(1f))
                        Text(
                            stringResource(R.string.tv_until, programClock(context, program.end)),
                            style = Antresol.typography.badge,
                            color = content.copy(alpha = 0.75f),
                        )
                    }
                }
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
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProgrammeStill(
            url = urls?.image(programmeArt(program)),
            shape = AntresolShapes.card,
            modifier = Modifier.fillMaxWidth().aspectRatio(AntresolMotion.BACKDROP_ASPECT),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
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
                Text(program.overview, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 20.dp)) {
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

