package app.antresol.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.core.model.Genre
import app.antresol.core.model.Shelf
import app.antresol.tv.R as TvR
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.ShelfRow
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.cardFocusKey
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.tv.ui.load
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What a shelf page shows: server shelves and, for Music, the genres. */
data class ShelfPage(val shelves: List<Shelf>, val genres: List<Genre> = emptyList())

/** Loads a shelf page such as `/api/music/home` or `/api/books/home`; empty shelves are dropped. */
class ShelvesViewModel(
    private val loader: suspend () -> List<Shelf>,
    private val genres: (suspend () -> List<Genre>)? = null,
) : ViewModel() {
    var state: LoadState<ShelfPage> by mutableStateOf(LoadState.Loading)
        private set

    init {
        reload()
    }

    fun reload() {
        state = LoadState.Loading
        viewModelScope.launch {
            state = load {
                coroutineScope {
                    val genreList = genres?.let { async { runCatching { it() }.getOrDefault(emptyList()) } }
                    val shelves = loader().filter { it.cards.isNotEmpty() }
                    ShelfPage(shelves, genreList?.await().orEmpty())
                }
            }
        }
    }
}

/**
 * A tab made of server shelves (Music, Books): page title with header links on the right
 * ([actions]), optional genre chips, then rows of cards. Focus starts on the first card.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShelvesScreen(
    title: String,
    viewModel: ShelvesViewModel,
    onOpen: (Card) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    onGenre: (Genre) -> Unit = {},
) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }

    val state = viewModel.state
    val page = (state as? LoadState.Ready)?.value
    LaunchedEffect(page) {
        val first = page?.shelves?.firstOrNull() ?: return@LaunchedEffect
        if (canTakeFocus()) focus.restore(cardFocusKey(first.id, first.cards.first()))
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
                    Text(title, style = Antresol.typography.pageTitle)
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.trapHorizontalFocus(),
                        content = actions,
                    )
                }
            }
            when (state) {
                LoadState.Loading -> item(key = "loading") { LoadingView(Modifier.padding(top = 120.dp)) }
                is LoadState.Failed -> item(key = "error") { ErrorView(state.error, onRetry = viewModel::reload, modifier = Modifier.padding(top = 60.dp), takeFocus = canTakeFocus()) }
                is LoadState.Ready -> {
                    val genres = state.value.genres
                    if (genres.isNotEmpty()) {
                        item(key = "genres") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    stringResource(TvR.string.music_genres),
                                    style = Antresol.typography.sectionTitle,
                                    modifier = Modifier.padding(horizontal = ScreenPaddingH),
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .trapHorizontalFocus()
                                        .focusRestorer(),
                                ) {
                                    items(genres, key = { it.name }) { genre ->
                                        PillButton(
                                            text = genre.name,
                                            onClick = { onGenre(genre) },
                                            modifier = Modifier.remembersFocus(focus, "genre/${genre.name}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.value.shelves.isEmpty()) {
                        item(key = "empty") { MessageView(stringResource(R.string.state_empty), Modifier.padding(top = 120.dp)) }
                    }
                    items(state.value.shelves, key = { it.id }) { shelf ->
                        ShelfRow(shelfId = shelf.id, title = shelf.title, cards = shelf.cards, onOpen = onOpen, focus = focus)
                    }
                }
            }
        }
    }
}
