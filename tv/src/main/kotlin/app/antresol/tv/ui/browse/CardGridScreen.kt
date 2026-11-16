package app.antresol.tv.ui.browse

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.ui.LoadState
import app.antresol.tv.ui.components.CardShape
import app.antresol.tv.ui.components.CardSizes
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MediaCard
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.SegmentedPills
import app.antresol.tv.ui.components.TopNavHeight
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.remembersGroupFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol

/** Distance from the end of the loaded cards at which the next page is requested. */
private const val PREFETCH_DISTANCE = 18

/**
 * A library grid (Movies, TV Shows, albums, artists, playlists): page title, segmented sort pills,
 * an optional Shuffle pill (genre albums), and the cards in the source's shape.
 *
 * The grid scrolls in the area below the top navigation and never under it, so the navigation
 * stays up; the columns share the width the fixed card size leaves over, so the first and last
 * column sit on the page gutters alike.
 */
@Composable
fun CardGridScreen(
    title: String,
    viewModel: CardGridViewModel,
    onOpen: (Card) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
    onShuffle: (() -> Unit)? = null,
) {
    val focus = rememberFocusMemory()
    val gridState = rememberLazyGridState()
    // Nothing scrolls under the navigation, so it never needs to fade out.
    LaunchedEffect(Unit) { onScrolledToTop(true) }

    val source = viewModel.source
    val sortLabels = source.sorts.map { stringResource(it.label) }
    val cardWidth = when (source.shape) {
        CardShape.Poster -> CardSizes.posterWidth
        CardShape.Wide -> CardSizes.wideWidth
        CardShape.Square, CardShape.Round -> CardSizes.squareWidth
    }
    val state = viewModel.state
    val firstKey = (state as? LoadState.Ready)?.value?.cards?.firstOrNull()?.let { gridKey(it) }
    // Restore focus once per arrival; later reloads (a new sort) leave focus on the sort pills.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(firstKey) {
        if (firstKey == null || arrived) return@LaunchedEffect
        arrived = true
        // Entered later from the nav (it had focus on arrival), the grid starts on its first card.
        focus.prefer(firstKey)
        focus.restore(firstKey, canTakeFocus)
    }

    VerticalShelfScrolling(leadingFraction = 0.12f, trailingFraction = 0.14f) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(cardWidth),
            state = gridState,
            // Room above the first row for its focus lift and glow inside the clipped area.
            contentPadding = PaddingValues(start = ScreenPaddingH, end = ScreenPaddingH, top = 8.dp, bottom = 48.dp),
            horizontalArrangement = EvenColumns(18.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = TopNavHeight)
                .clipToBounds()
                .trapHorizontalFocus()
                .focusGroup(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The title takes the free width, so the pills sit flush right.
                    Text(title, style = Antresol.typography.pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onShuffle != null) {
                            PillButton(
                                stringResource(R.string.action_shuffle),
                                onClick = onShuffle,
                                icon = TvIcons.Shuffle,
                                modifier = Modifier.remembersFocus(focus, "header/shuffle"),
                            )
                        }
                        if (sortLabels.size > 1) {
                            SegmentedPills(
                                options = sortLabels,
                                selectedIndex = viewModel.sortIndex,
                                onSelect = viewModel::select,
                                modifier = Modifier.remembersGroupFocus(focus, "header/sort"),
                            )
                        }
                    }
                }
            }
            when (state) {
                LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = 120.dp)) { LoadingView() } }
                is LoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(top = 60.dp)) { ErrorView(state.error, onRetry = viewModel::reload, takeFocus = canTakeFocus()) }
                }
                is LoadState.Ready ->
                    if (state.value.cards.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.padding(top = 120.dp)) { MessageView(stringResource(R.string.state_empty)) }
                        }
                    } else {
                        val cards = state.value.cards
                        itemsIndexed(cards, key = { _, card -> gridKey(card) }, contentType = { _, _ -> "card" }) { index, card ->
                            MediaCard(
                                card = card,
                                onClick = { onOpen(card) },
                                shape = source.shape,
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

/**
 * Columns at least [spacing] apart that share what a whole number of fixed-size columns leaves
 * over, so the first column starts on the start gutter and the last one ends on the end gutter.
 */
private class EvenColumns(override val spacing: Dp) : Arrangement.Horizontal {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        if (sizes.size < 2) {
            with(Arrangement.Start) { arrange(totalSize, sizes, layoutDirection, outPositions) }
            return
        }
        val gap = (totalSize - sizes.sum()).coerceAtLeast(0).toFloat() / (sizes.size - 1)
        var x = 0f
        for (i in sizes.indices) {
            val start = x.roundToInt()
            outPositions[i] = if (layoutDirection == LayoutDirection.Ltr) start else totalSize - start - sizes[i]
            x += sizes[i] + gap
        }
    }
}
