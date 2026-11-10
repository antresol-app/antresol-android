package app.antresol.mobile.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.antresol.core.model.Channel
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.CardSizes
import app.antresol.mobile.ui.components.CardStyle
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PageTitle
import app.antresol.mobile.ui.components.PageTop
import app.antresol.mobile.ui.components.RefreshBox
import app.antresol.mobile.ui.components.SectionTitle
import app.antresol.mobile.ui.components.SegmentedPills
import app.antresol.mobile.ui.components.fullBleed
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/**
 * «TV»: the live channels. The section pills pick «All» (every section as a row of channel cards)
 * or one section (its channels as a grid). A card opens the live player. The list refreshes every
 * 30 s while the page is on screen, and on pull.
 */
@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel,
    onWatch: (Channel) -> Unit,
    onBack: (() -> Unit)?,
) {
    LifecycleStartEffect(viewModel) {
        viewModel.startRefreshing()
        onStopOrDispose { viewModel.stopRefreshing() }
    }
    val bottom = LocalBottomBarPadding.current
    val direction = LocalLayoutDirection.current
    val nowMs = rememberNowMs()
    val loaded = viewModel.sections
    val error = viewModel.error
    val sections = loaded.orEmpty()
    val selected = viewModel.selectedSection
    RefreshBox(viewModel.refreshing, onRefresh = viewModel::pullToRefresh, Modifier.fillMaxSize().background(AntresolColors.Background)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CardSizes.gridMinWidth(CardStyle.Wide)),
            contentPadding = PaddingValues(
                start = PageGutter + bottom.calculateStartPadding(direction),
                end = PageGutter + bottom.calculateEndPadding(direction),
                bottom = bottom.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            fullWidth("top") { Box(Modifier.fullBleed(PageGutter)) { PageTop(onBack) } }
            fullWidth("title") { PageTitle(stringResource(R.string.nav_tv), Modifier.fullBleed(PageGutter)) }
            if (sections.size > 1) {
                fullWidth("sections") {
                    SegmentedPills(
                        options = listOf<String?>(null) + sections.map { it.category },
                        selected = selected,
                        label = { it ?: stringResource(R.string.tv_all_sections) },
                        onSelect = viewModel::select,
                        modifier = Modifier.fullBleed(PageGutter),
                    )
                }
            }
            when {
                error != null -> fullWidth("error") { ErrorView(error, onRetry = viewModel::reload) }
                loaded == null -> fullWidth("loading") { LoadingView() }
                sections.isEmpty() -> fullWidth("empty") { EmptyChannels() }
                selected == null -> viewModel.shown.forEach { section ->
                    fullWidth("row/${section.category}") {
                        ChannelShelf(section.category, section.channels, nowMs, onWatch, Modifier.fullBleed(PageGutter))
                    }
                }
                else -> items(viewModel.shown.first().channels, key = { "channel/${it.id}" }) { channel ->
                    ChannelCard(channel, nowMs, onClick = { onWatch(channel) })
                }
            }
        }
    }
}

/** A titled row of channel cards (a section of «All», or «On Air Now» on Home). */
@Composable
fun ChannelShelf(
    title: String,
    channels: List<Channel>,
    nowMs: Long,
    onWatch: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        SectionTitle(title, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                ChannelCard(channel, nowMs, onClick = { onWatch(channel) }, width = CardSizes.width(CardStyle.Wide))
            }
        }
    }
}

@Composable
private fun EmptyChannels() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.tv_empty), style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.tv_empty_hint),
            style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
            textAlign = TextAlign.Center,
        )
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
