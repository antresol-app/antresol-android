package app.antresol.mobile.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.antresol.core.channels.ChannelFilter
import app.antresol.ui.channels.channelFilterTitle
import app.antresol.core.model.Channel
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.PageTitle
import app.antresol.mobile.ui.components.PageTop
import app.antresol.mobile.ui.components.RefreshBox
import app.antresol.mobile.ui.components.SegmentedPills
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsView
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** This page's turn at the channel refresh it shares with Home's «On Air Now». */
private const val TV_PAGE_WATCHER = "tv-page"

/**
 * «ТВ», a tab of its own: ONE numbered list of channels, like a television («Каналы»), or the
 * programme guide of the same list («Телегид»). Filters «Все · Избранное · <themes>» narrow both.
 * The list opens on the profile's last channel (highlighted). A tap tunes in (the player zaps
 * inside the list it was opened from); a long press toggles the favourite. Refreshes every 30 s
 * while shown, and on pull.
 */
@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel,
    onWatch: (Channel, ChannelFilter) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    LifecycleStartEffect(viewModel) {
        viewModel.opened()
        viewModel.startRefreshing(TV_PAGE_WATCHER)
        onStopOrDispose { viewModel.stopRefreshing(TV_PAGE_WATCHER) }
    }
    val bottom = LocalBottomBarPadding.current
    val direction = LocalLayoutDirection.current
    val nowMs = rememberNowMs()
    val loaded = viewModel.channels
    val error = viewModel.error
    val filter = viewModel.filter
    val shown = viewModel.shown
    val last = viewModel.lastChannel
    val watch: (Channel) -> Unit = { channel -> onWatch(channel, filter) }
    val padding = PaddingValues(
        start = bottom.calculateStartPadding(direction),
        end = bottom.calculateEndPadding(direction),
        bottom = bottom.calculateBottomPadding() + 16.dp,
    )

    val header: LazyListScope.() -> Unit = {
        item("top") { PageTop(onBack) }
        item("title") { TitleRow(viewModel) }
        if (loaded != null && loaded.isNotEmpty()) item("filters") { FilterRow(viewModel) }
    }

    when {
        viewModel.view == ChannelsView.Guide && loaded != null && loaded.isNotEmpty() -> GuidePage(viewModel, nowMs, padding, watch)
        else -> {
            val listState = rememberLazyListState()
            var scrolledToLast by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(last?.id, shown.size) {
                // Open on the last channel (a TV turns on where it was left), once.
                val index = shown.indexOfFirst { it.id == last?.id }
                if (!scrolledToLast && index >= 0) {
                    scrolledToLast = true
                    if (index > 2) listState.scrollToItem(HEADER_ITEMS + index - 1)
                }
            }
            RefreshBox(viewModel.refreshing, onRefresh = viewModel::pullToRefresh, Modifier.fillMaxSize().background(AntresolColors.Background)) {
                LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize().testTag("channel-list")) {
                    header()
                    when {
                        error != null -> item("error") { ErrorView(error, onRetry = viewModel::reload) }
                        loaded == null -> item("loading") { LoadingView() }
                        loaded.isEmpty() -> item("empty") { Empty(stringResource(R.string.tv_empty), stringResource(R.string.tv_empty_hint)) }
                        shown.isEmpty() && filter == ChannelFilter.Favorites -> item("no-favorites") {
                            Empty(stringResource(R.string.tv_favorites_empty), stringResource(R.string.tv_favorites_empty_hint))
                        }
                        else -> items(shown, key = { it.id }) { channel ->
                            // Names line up when some channel of the list has a logo.
                            androidx.compose.runtime.CompositionLocalProvider(
                                app.antresol.ui.channels.LocalReserveChannelLogo provides app.antresol.ui.channels.reserveLogoSlot(shown.map { it.logoUrl }),
                            ) {
                            ChannelRow(
                                channel = channel,
                                nowMs = nowMs,
                                onClick = { watch(channel) },
                                onToggleFavorite = { viewModel.toggleFavorite(channel) },
                                last = channel.id == last?.id,
                                modifier = Modifier.padding(vertical = 2.dp).testTag("channel-${channel.number}"),
                            )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Items before the first channel row: the header, the title row, the filters. */
private const val HEADER_ITEMS = 3

/** «ТВ» and the view switch «Каналы | Телегид». */
@Composable
private fun TitleRow(viewModel: ChannelsViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PageTitle(stringResource(R.string.nav_tv), Modifier.weight(1f))
        if (!viewModel.channels.isNullOrEmpty()) {
            SegmentedPills(
                options = ChannelsView.entries,
                selected = viewModel.view,
                label = { stringResource(if (it == ChannelsView.Channels) R.string.tv_view_channels else R.string.tv_view_guide) },
                onSelect = viewModel::showView,
                modifier = Modifier.testTag("tv-view"),
            )
        }
    }
}

/**
 * «Все · Избранное · <categories>»: one row that scrolls when it does not fit. The categories are
 * the server's, in its order: its AI topics first, then the sources, then the playlists' groups
 * (an older server: the channels' own category texts).
 */
@Composable
private fun FilterRow(viewModel: ChannelsViewModel) {
    val options = viewModel.filterOptions
    val names = viewModel.serverCategories.orEmpty()
    SegmentedPills(
        options = options.map { it.filter },
        selected = viewModel.filter,
        label = { option -> if (option == ChannelFilter.All) stringResource(R.string.tv_all_sections) else channelFilterTitle(option, names) },
        onSelect = viewModel::select,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).testTag("tv-filters"),
    )
}

@Composable
private fun GuidePage(viewModel: ChannelsViewModel, nowMs: Long, padding: PaddingValues, onWatch: (Channel) -> Unit) {
    var pick by androidx.compose.runtime.remember { mutableStateOf<GuidePick?>(null) }
    val rows = viewModel.guideRows
    val guideError = viewModel.guideError
    Column(Modifier.fillMaxSize().background(AntresolColors.Background)) {
        PageTop(null)
        TitleRow(viewModel)
        FilterRow(viewModel)
        val shown = viewModel.shownGuide
        when {
            guideError != null -> ErrorView(guideError, onRetry = viewModel::loadGuide)
            rows == null -> LoadingView()
            shown.isEmpty() && viewModel.filter == ChannelFilter.Favorites ->
                Empty(stringResource(R.string.tv_favorites_empty), stringResource(R.string.tv_favorites_empty_hint))
            else -> GuideGrid(
                rows = shown,
                fromMs = viewModel.guideFromMs,
                toMs = viewModel.guideToMs,
                nowMs = nowMs,
                lastChannelId = viewModel.lastChannel?.id,
                onPick = { pick = it },
                onWatchChannel = { id -> viewModel.channels?.firstOrNull { it.id == id }?.let(onWatch) },
                onNearEnd = viewModel::loadMoreGuide,
                contentPadding = padding,
                modifier = Modifier.weight(1f),
            )
        }
    }
    pick?.let { picked ->
        val channel = viewModel.channels?.firstOrNull { it.id == picked.channelId }
        ProgramSheet(
            pick = picked.copy(favorite = channel?.favorite ?: picked.favorite),
            nowMs = nowMs,
            onWatch = {
                pick = null
                channel?.let(onWatch)
            },
            onToggleFavorite = { channel?.let(viewModel::toggleFavorite) },
            onDismiss = { pick = null },
        )
    }
}

@Composable
private fun Empty(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
        Text(hint, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), textAlign = TextAlign.Center)
    }
}

