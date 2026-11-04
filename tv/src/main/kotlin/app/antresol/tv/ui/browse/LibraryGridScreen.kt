package app.antresol.tv.ui.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.CardShape
import app.antresol.tv.ui.components.CardSizes
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MediaCard
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.SegmentedPills
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol

/** Distance from the end of the loaded cards at which the next page is requested. */
private const val PREFETCH_DISTANCE = 18

/** Movies or TV Shows: page title, segmented sort pills and a poster grid. */
@Composable
fun LibraryGridScreen(
    @StringRes title: Int,
    viewModel: LibraryGridViewModel,
    onOpen: (Card) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val focus = rememberFocusMemory()
    val gridState = rememberLazyGridState()
    val atTop by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }

    val sortLabels = listOf(
        stringResource(R.string.sort_recent),
        stringResource(R.string.sort_title),
        stringResource(R.string.sort_year),
    )
    val state = viewModel.state
    val firstKey = (state as? LoadState.Ready)?.value?.cards?.firstOrNull()?.let { gridKey(it) }
    // Restore focus once per arrival; later reloads (a new sort) leave focus on the sort pills.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(firstKey) {
        if (firstKey == null || arrived) return@LaunchedEffect
        arrived = true
        if (canTakeFocus()) focus.restore(firstKey)
    }

    VerticalShelfScrolling(leadingFraction = 0.2f, trailingFraction = 0.14f) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(CardSizes.posterWidth),
            state = gridState,
            contentPadding = PaddingValues(start = ScreenPaddingH, end = ScreenPaddingH, top = 84.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier
                .fillMaxSize()
                .trapHorizontalFocus()
                .focusGroup(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(title), style = Antresol.typography.pageTitle)
                    Spacer(Modifier.weight(1f))
                    SegmentedPills(
                        options = sortLabels,
                        selectedIndex = viewModel.sort.ordinal,
                        onSelect = { viewModel.select(LibrarySort.entries[it]) },
                    )
                }
            }
            when (state) {
                LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = 120.dp)) { LoadingView() } }
                is LoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(top = 60.dp)) { ErrorView(state.error, onRetry = viewModel::reload) }
                }
                is LoadState.Ready ->
                    if (state.value.cards.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.padding(top = 120.dp)) { MessageView(stringResource(R.string.state_empty)) }
                        }
                    } else {
                        val cards = state.value.cards
                        itemsIndexed(cards, key = { _, card -> gridKey(card) }) { index, card ->
                            MediaCard(
                                card = card,
                                onClick = { onOpen(card) },
                                shape = CardShape.Poster,
                                surfaceModifier = Modifier
                                    .remembersFocus(focus, gridKey(card))
                                    .onFocusChanged {
                                        if (it.isFocused && index >= cards.size - PREFETCH_DISTANCE) viewModel.loadMore()
                                    },
                            )
                        }
                    }
            }
        }
    }
}

private fun gridKey(card: Card) = "grid/${card.kind}/${card.id}"
