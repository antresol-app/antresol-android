package app.antresol.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.Card
import app.antresol.core.model.Shelf
import app.antresol.core.model.ShelfId
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LoadViewModel
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.CardStyle
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.Shelf
import app.antresol.mobile.ui.components.TabHeader
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
    onPlay: (Card) -> Unit,
    onSeeAll: (Shelf) -> Unit,
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val bottom = LocalBottomBarPadding.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AntresolColors.Background),
        contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 16.dp),
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
                        Box {
                            Hero(content.hero, content.heroSource, onPlay = onPlay, onDetails = onOpen)
                            TabHeader(Modifier.align(Alignment.TopCenter))
                        }
                    }
                }
                if (content.isEmpty) item("empty") { EmptyView(stringResource(R.string.home_empty)) }
                items(content.shelves, key = { it.id }) { shelf ->
                    Shelf(
                        title = shelf.title,
                        cards = shelf.cards,
                        onOpen = onOpen,
                        style = if (shelf.id == ShelfId.CONTINUE) CardStyle.Wide else null,
                        onSeeAll = { onSeeAll(shelf) },
                    )
                }
            }
        }
    }
}
