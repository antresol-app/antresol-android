package app.antresol.mobile.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.Card
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.CardSizes
import app.antresol.mobile.ui.components.CardStyle
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MediaCard
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PageTitle
import app.antresol.mobile.ui.components.PageTop
import app.antresol.mobile.ui.components.PagingFooter
import app.antresol.mobile.ui.components.RefreshBox
import app.antresol.mobile.ui.components.SegmentedPills
import app.antresol.mobile.ui.components.defaultStyle
import app.antresol.mobile.ui.components.fullBleed
import app.antresol.ui.R
import app.antresol.ui.theme.AntresolColors

/**
 * A titled, adaptive grid of cards with sort pills (Movies, TV Shows, All Albums, a shelf's "See all").
 * Tab roots pass [onBack] = null and get the tab header; pushed pages get a back button.
 */
@Composable
fun CardGridScreen(
    title: String,
    viewModel: CardGridViewModel,
    onOpen: (Card) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val source = viewModel.source
    val style = source.style
    val gridState = rememberLazyGridState()
    val bottom = LocalBottomBarPadding.current
    val direction = LocalLayoutDirection.current

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 12
        }
    }
    // A failed page waits for "Try Again" instead of retrying on every layout pass.
    LaunchedEffect(nearEnd, viewModel.state) {
        if (nearEnd && viewModel.pagingError == null) viewModel.loadMore()
    }
    // New titles and progress show up when the page comes back into view.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    RefreshBox(viewModel.refreshing, onRefresh = { viewModel.refresh(pulled = true) }, Modifier.fillMaxSize().background(AntresolColors.Background)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CardSizes.gridMinWidth(style ?: CardStyle.Poster)),
            state = gridState,
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
            fullWidth("title") { PageTitle(title, Modifier.fullBleed(PageGutter)) }
            val sorts = source.sorts
            val sort = viewModel.sort
            if (sorts.isNotEmpty() && sort != null) {
                fullWidth("sorts") {
                    // The Box gives the pills loose width constraints, so they hug their content on tablets.
                    Box(Modifier.fillMaxWidth()) {
                        SegmentedPills(
                        options = sorts,
                        selected = sort,
                        label = { stringResource(it.label) },
                            onSelect = viewModel::select,
                        )
                    }
                }
            }
            when (val state = viewModel.state) {
                LoadState.Loading -> fullWidth("loading") { LoadingView() }
                is LoadState.Failed -> fullWidth("error") { ErrorView(state.error, onRetry = viewModel::reload) }
                is LoadState.Ready -> {
                    if (state.value.items.isEmpty()) {
                        fullWidth("empty") { EmptyView(stringResource(R.string.state_empty)) }
                    }
                    items(state.value.items, key = { "${it.kind}:${it.id}" }) { card ->
                        MediaCard(card = card, onClick = { onOpen(card) }, style = style ?: card.defaultStyle())
                    }
                    fullWidth("paging") { PagingFooter(viewModel.loadingMore, viewModel.pagingError, onRetry = viewModel::loadMore) }
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
