package app.antresol.tv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.antresol.core.model.Card
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.ShelfRow
import app.antresol.tv.ui.components.TvTextField
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.ui.R

private const val FIELD_KEY = "search/field"

/** Search: a text field (OK opens the keyboard) and result rows by section. OK on a song plays it, long press opens its album. */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpen: (Card) -> Unit,
    onNowPlaying: () -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    LaunchedEffect(Unit) { if (canTakeFocus()) focus.restore(FIELD_KEY) }

    VerticalShelfScrolling {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 84.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "field") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TvTextField(
                        value = viewModel.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = stringResource(R.string.search_hint),
                        onSubmit = {},
                        imeAction = ImeAction.Search,
                        modifier = Modifier
                            .width(560.dp)
                            .remembersFocus(focus, FIELD_KEY),
                    )
                }
            }
            when (val results = viewModel.results) {
                null -> item(key = "prompt") { Placeholder { MessageView(stringResource(R.string.search_prompt)) } }
                LoadState.Loading -> item(key = "loading") { Placeholder { LoadingView() } }
                is LoadState.Failed -> item(key = "error") { Placeholder { ErrorView(results.error, onRetry = viewModel::retry, takeFocus = false) } }
                is LoadState.Ready ->
                    if (results.value.isEmpty()) {
                        item(key = "empty") { Placeholder { MessageView(stringResource(R.string.search_no_results, viewModel.query)) } }
                    } else {
                        items(results.value, key = { it.id }) { row ->
                            val songs = row.id == SONGS_ROW
                            ShelfRow(
                                shelfId = row.id,
                                title = row.titleRes?.let { stringResource(it) } ?: row.title,
                                cards = row.cards,
                                onOpen = if (songs) { card -> viewModel.playSong(card, onNowPlaying) } else onOpen,
                                focus = focus,
                                onLongPress = if (songs) { card -> viewModel.songAlbum(card)?.let(onOpen) } else null,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun Placeholder(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(horizontal = ScreenPaddingH),
    ) { content() }
}
