package app.antresol.tv.ui.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.core.model.Shelf
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.ShelfRow
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.cardFocusKey
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.load
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import kotlinx.coroutines.launch

/** Loads a shelf page such as `/api/music/home` or `/api/books/home`; empty shelves are dropped. */
class ShelvesViewModel(private val loader: suspend () -> List<Shelf>) : ViewModel() {
    var state: LoadState<List<Shelf>> by mutableStateOf(LoadState.Loading)
        private set

    init {
        reload()
    }

    fun reload() {
        state = LoadState.Loading
        viewModelScope.launch { state = load { loader().filter { it.cards.isNotEmpty() } } }
    }
}

/** A tab made of server shelves (Music, Books): page title and rows of cards. */
@Composable
fun ShelvesScreen(
    @StringRes title: Int,
    viewModel: ShelvesViewModel,
    onOpen: (Card) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }

    when (val state = viewModel.state) {
        LoadState.Loading -> LoadingView()
        is LoadState.Failed -> ErrorView(state.error, onRetry = viewModel::reload)
        is LoadState.Ready -> {
            val shelves = state.value
            if (shelves.isEmpty()) {
                MessageView(stringResource(R.string.state_empty))
                return
            }
            LaunchedEffect(Unit) {
                if (canTakeFocus()) focus.restore(cardFocusKey(shelves.first().id, shelves.first().cards.first()))
            }
            VerticalShelfScrolling {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 84.dp, bottom = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "title") {
                        Text(
                            stringResource(title),
                            style = Antresol.typography.pageTitle,
                            modifier = Modifier.padding(horizontal = ScreenPaddingH),
                        )
                    }
                    items(shelves, key = { it.id }) { shelf ->
                        ShelfRow(shelfId = shelf.id, title = shelf.title, cards = shelf.cards, onOpen = onOpen, focus = focus)
                    }
                }
            }
        }
    }
}
