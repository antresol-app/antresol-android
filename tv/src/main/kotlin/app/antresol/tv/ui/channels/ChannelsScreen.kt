package app.antresol.tv.ui.channels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import app.antresol.core.channels.ChannelSection
import app.antresol.core.model.Channel
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.FocusMemory
import app.antresol.tv.ui.components.HorizontalRowScrolling
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.SegmentedPills
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.remembersGroupFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** Cards per row when one section is shown: three 252 dp cards fit the 864 dp between the gutters. */
private const val SECTION_COLUMNS = 3

/**
 * The «TV» tab: page title with the section pills («All» and every category), then either every
 * section as a row of channel cards or the picked section as rows of three. OK on a card opens the
 * live player. Focus starts on the first channel and comes back to the opened one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel,
    onWatch: (Channel) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.startRefreshing() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopRefreshing() }
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    val nowMs = rememberNowMs()

    val loaded = viewModel.sections
    val error = viewModel.error
    val sections = loaded.orEmpty()
    val shown = viewModel.shown
    val selected = viewModel.selectedSection
    val first = shown.firstOrNull()?.let { channelKey(it, it.channels.first()) }
    LaunchedEffect(first != null) {
        if (first != null && canTakeFocus()) focus.restore(first)
    }

    VerticalShelfScrolling {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 84.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPaddingH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.nav_tv), style = Antresol.typography.pageTitle)
                    Spacer(Modifier.width(24.dp))
                    if (sections.size > 1) {
                        val labels = listOf(stringResource(R.string.tv_all_sections)) + sections.map { it.category }
                        val sectionsLabel = stringResource(R.string.tv_sections)
                        // Many sections scroll inside the header rather than squeezing the title.
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            SegmentedPills(
                                options = labels,
                                selectedIndex = selected?.let { s -> sections.indexOfFirst { it.category == s } + 1 } ?: 0,
                                onSelect = { index -> viewModel.select(sections.getOrNull(index - 1)?.category) },
                                modifier = Modifier
                                    .semantics { contentDescription = sectionsLabel }
                                    .horizontalScroll(rememberScrollState())
                                    .remembersGroupFocus(focus, "sections"),
                            )
                        }
                    }
                }
            }
            when {
                error != null -> item(key = "error") {
                    ErrorView(error, onRetry = viewModel::reload, modifier = Modifier.padding(top = 60.dp), takeFocus = canTakeFocus())
                }
                loaded == null -> item(key = "loading") { LoadingView(Modifier.padding(top = 120.dp)) }
                else -> when {
                    sections.isEmpty() -> item(key = "empty") { EmptyChannels(Modifier.padding(top = 100.dp)) }
                    selected == null -> items(shown, key = { "row/${it.category}" }) { section ->
                        ChannelRow(section, nowMs, focus, onWatch)
                    }
                    else -> {
                        val section = shown.first()
                        items(section.channels.chunked(SECTION_COLUMNS), key = { row -> "grid/${row.first().id}" }) { row ->
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = ScreenPaddingH)
                                    .trapHorizontalFocus()
                                    .focusRestorer(),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                row.forEach { channel ->
                                    ChannelCard(
                                        channel = channel,
                                        nowMs = nowMs,
                                        onClick = { onWatch(channel) },
                                        surfaceModifier = Modifier.remembersFocus(focus, channelKey(section, channel)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A section title and its channels in a row. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelRow(section: ChannelSection, nowMs: Long, focus: FocusMemory, onWatch: (Channel) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = section.category,
            style = Antresol.typography.sectionTitle,
            modifier = Modifier.padding(horizontal = ScreenPaddingH),
        )
        HorizontalRowScrolling {
            LazyRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .trapHorizontalFocus()
                    .focusRestorer(),
            ) {
                items(section.channels, key = { it.id }) { channel ->
                    ChannelCard(
                        channel = channel,
                        nowMs = nowMs,
                        onClick = { onWatch(channel) },
                        surfaceModifier = Modifier.remembersFocus(focus, channelKey(section, channel)),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChannels(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.tv_empty), style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.tv_empty_hint),
            style = Antresol.typography.body,
            color = AntresolColors.LabelSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
    }
}

private fun channelKey(section: ChannelSection, channel: Channel) = "channel/${section.category}/${channel.id}"
