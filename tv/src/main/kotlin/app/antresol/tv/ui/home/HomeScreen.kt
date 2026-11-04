package app.antresol.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.core.model.Card
import app.antresol.core.model.ShelfId
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.CardShape
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.ShelfRow
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.cardFocusKey
import app.antresol.tv.ui.components.defaultShape
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PLAY_KEY = "hero/play"
private const val DETAILS_KEY = "hero/details"
private const val HERO_ROTATE_MS = 10_000L

/**
 * Home: the immersive hero (Play focused on arrival) over the `/api/home` shelves, Continue
 * Watching as wide cards. Focus returns to the last focused element when coming back.
 *
 * [canTakeFocus] is false while the top navigation has focus, so switching tabs does not pull
 * focus out of the navigation.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpen: (Card) -> Unit,
    onPlay: (Card) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }
    when (val state = viewModel.state) {
        LoadState.Loading -> LoadingView()
        is LoadState.Failed -> ErrorView(state.error, onRetry = viewModel::reload)
        is LoadState.Ready ->
            if (state.value.isEmpty) {
                LaunchedEffect(Unit) { onScrolledToTop(true) }
                MessageView(stringResource(R.string.home_empty))
            } else {
                HomeContentView(state.value, onOpen, onPlay, onScrolledToTop, canTakeFocus)
            }
    }
}

@Composable
private fun HomeContentView(
    content: HomeContent,
    onOpen: (Card) -> Unit,
    onPlay: (Card) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    var heroIndex by rememberSaveable { mutableIntStateOf(0) }
    val heroCard = content.hero.getOrNull(heroIndex % content.hero.size.coerceAtLeast(1))
    var heroFocused by remember { mutableStateOf(false) }
    val homeKey = when {
        heroCard != null -> PLAY_KEY
        else -> content.shelves.first().let { cardFocusKey(it.id, it.cards.first()) }
    }

    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }

    // The background dims as the shelves scroll over it.
    val backgroundAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                0.3f
            } else {
                val heroPx = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
                1f - 0.7f * (listState.firstVisibleItemScrollOffset.toFloat() / heroPx).coerceIn(0f, 1f)
            }
        }
    }

    // Arrival (first launch or back from a detail page): focus Play or the remembered card.
    LaunchedEffect(Unit) {
        if (canTakeFocus()) focus.restore(homeKey)
    }

    // Rotate through the hero cards while the user rests on the hero buttons.
    LaunchedEffect(heroFocused, heroIndex, content.hero.size) {
        if (heroFocused && content.hero.size > 1) {
            delay(HERO_ROTATE_MS)
            heroIndex = (heroIndex + 1) % content.hero.size
        }
    }

    // Back from deep in the shelves returns to the top first, then leaves the app.
    BackHandler(enabled = focus.focusedKey != null && focus.focusedKey != homeKey) {
        scope.launch {
            listState.animateScrollToItem(0)
            focus.requester(homeKey).tryRequestFocus()
        }
    }

    Box(Modifier.fillMaxSize()) {
        HeroBackground(heroCard, Modifier.graphicsLayer { alpha = backgroundAlpha })
        VerticalShelfScrolling {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (heroCard != null) {
                    item(key = "hero") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(heroHeight)
                                .padding(start = ScreenPaddingH, end = ScreenPaddingH, bottom = 4.dp),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            HeroInfo(
                                card = heroCard,
                                heroSource = content.heroSource,
                                count = content.hero.size,
                                index = heroIndex % content.hero.size,
                                playButton = {
                                    HeroPlayButton(heroCard, onClick = { onPlay(heroCard) }, modifier = Modifier.remembersFocus(focus, PLAY_KEY))
                                },
                                detailsButton = {
                                    HeroDetailsButton(onClick = { onOpen(heroCard) }, modifier = Modifier.remembersFocus(focus, DETAILS_KEY))
                                },
                                modifier = Modifier.onFocusChanged {
                                    heroFocused = it.hasFocus
                                    // The hero is taller than the focus zone; show all of it again.
                                    if (it.hasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                                        scope.launch { listState.animateScrollToItem(0) }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    item(key = "top") { Spacer(Modifier.height(88.dp)) }
                }
                items(content.shelves, key = { it.id }) { shelf ->
                    ShelfRow(
                        shelfId = shelf.id,
                        title = shelf.title,
                        cards = shelf.cards,
                        onOpen = onOpen,
                        focus = focus,
                        shapeOf = if (shelf.id == ShelfId.CONTINUE) { _ -> CardShape.Wide } else { card -> card.defaultShape() },
                    )
                }
            }
        }
    }
}
