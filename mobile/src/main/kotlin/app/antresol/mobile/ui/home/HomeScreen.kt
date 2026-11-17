package app.antresol.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.Card
import app.antresol.core.model.HomeContent
import app.antresol.core.model.Shelf
import app.antresol.core.model.ShelfId
import app.antresol.ui.LoadState
import app.antresol.ui.LoadViewModel
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.CardStyle
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.PageSideInsets
import app.antresol.mobile.ui.components.RefreshBox
import app.antresol.mobile.ui.components.Shelf
import app.antresol.mobile.ui.components.TabHeader
import app.antresol.mobile.ui.components.fullBleed
import app.antresol.mobile.ui.components.pageListPadding
import app.antresol.ui.R
import app.antresol.ui.theme.AntresolColors

/**
 * Home: the hero (with the tab header floating over it) and every non-empty `/api/home` shelf.
 * Continue Watching uses wide 16:9 tiles. Comes back fresh after playback (refresh on resume).
 */
@Composable
fun HomeScreen(
    viewModel: LoadViewModel<HomeContent>,
    onOpen: (Card) -> Unit,
    /** A Continue Watching tile: carry on playing or reading (a long press opens its page). */
    onContinue: (Card) -> Unit,
    onPlay: (Card) -> Unit,
    onSeeAll: (Shelf) -> Unit,
    /** «On Air Now», after the first shelf (as on the web); draws nothing without channels. */
    onAir: @Composable () -> Unit = {},
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val bottom = LocalBottomBarPadding.current
    RefreshBox(viewModel.refreshing, onRefresh = viewModel::pullToRefresh, Modifier.fillMaxSize().background(AntresolColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pageListPadding(bottom.calculateBottomPadding() + 16.dp),
        ) {
            when (val state = viewModel.state) {
                LoadState.Loading -> {
                    item("top") { TabHeader() }
                    item("loading") { LoadingView() }
                }
                is LoadState.Failed -> {
                    item("top") { TabHeader() }
                    item("error") { ErrorView(state.error, onRetry = viewModel::reload) }
                }
                is LoadState.Ready -> {
                    val content = state.value
                    item("top") {
                        if (content.hero.isEmpty()) {
                            TabHeader()
                        } else {
                            // The hero's backdrop goes edge to edge; what lies over it keeps clear of the cutout.
                            val sides = PageSideInsets
                            Box(Modifier.fullBleed(sides)) {
                                Hero(content.hero, content.heroSource, onPlay = onPlay, onDetails = onOpen)
                                TabHeader(Modifier.align(Alignment.TopCenter).windowInsetsPadding(sides))
                            }
                        }
                    }
                    if (content.isEmpty) item("empty") { EmptyView(stringResource(R.string.home_empty)) }
                    if (content.shelves.isEmpty()) item("on-air") { onAir() }
                    content.shelves.forEachIndexed { index, shelf ->
                        item(shelf.id, contentType = "shelf") {
                            val continuing = shelf.id == ShelfId.CONTINUE
                            Shelf(
                                title = shelf.title,
                                cards = shelf.cards,
                                onOpen = if (continuing) onContinue else onOpen,
                                onLongPress = if (continuing) onOpen else null,
                                style = if (continuing) CardStyle.Wide else null,
                                onSeeAll = { onSeeAll(shelf) },
                            )
                        }
                        if (index == 0) item("on-air") { onAir() }
                    }
                }
            }
        }
    }
}
