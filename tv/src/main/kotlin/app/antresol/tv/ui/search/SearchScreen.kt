package app.antresol.tv.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.tv.R as TvR
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.FocusMemory
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.SegmentedPills
import app.antresol.tv.ui.components.ShelfRow
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.components.TvTextField
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.remembersGroupFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

private const val FIELD_KEY = "search/field"

/** The Browse tiles under an empty search: where each one leads. */
enum class BrowseTarget { Movies, Shows, Music, Books, Audiobooks, Comics }

/**
 * Search: the page title, a text field (OK opens the keyboard), scope pills and, while the query is
 * blank, the Browse tiles of every library; then result rows by section. OK on a song plays it, long
 * press opens its album.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpen: (Card) -> Unit,
    onNowPlaying: () -> Unit,
    onBrowse: (BrowseTarget) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    LaunchedEffect(Unit) { if (canTakeFocus()) focus.restore(FIELD_KEY) }
    val scopeLabels = listOf(
        stringResource(TvR.string.search_scope_all),
        stringResource(R.string.nav_movies),
        stringResource(R.string.nav_tv_shows),
        stringResource(R.string.nav_music),
        stringResource(R.string.nav_books),
    )

    VerticalShelfScrolling {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 84.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "field") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.nav_search), style = Antresol.typography.pageTitle)
                        TvTextField(
                            value = viewModel.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = stringResource(R.string.search_hint),
                            onSubmit = {},
                            imeAction = ImeAction.Search,
                            leadingIcon = AntresolIcons.Search,
                            modifier = Modifier
                                .fillMaxWidth()
                                .remembersFocus(focus, FIELD_KEY),
                        )
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SegmentedPills(
                                options = scopeLabels,
                                selectedIndex = viewModel.scope.ordinal,
                                onSelect = { viewModel.selectScope(SearchScope.entries[it]) },
                                modifier = Modifier.remembersGroupFocus(focus, "search/scope"),
                            )
                        }
                    }
                }
            }
            when (val results = viewModel.results) {
                null -> {
                    item(key = "browse") { BrowseRow(focus, onBrowse) }
                    item(key = "prompt") {
                        Text(
                            stringResource(R.string.search_prompt),
                            style = Antresol.typography.body,
                            color = AntresolColors.LabelSecondary,
                            modifier = Modifier.padding(horizontal = ScreenPaddingH),
                        )
                    }
                }
                LoadState.Loading -> item(key = "loading") { Placeholder { LoadingView() } }
                is LoadState.Failed -> item(key = "error") { Placeholder { ErrorView(results.error, onRetry = viewModel::retry, takeFocus = false) } }
                is LoadState.Ready -> {
                    val rows = viewModel.scope.filter(results.value)
                    if (rows.isEmpty()) {
                        item(key = "empty") { Placeholder { MessageView(stringResource(R.string.search_no_results, viewModel.query)) } }
                    } else {
                        items(rows, key = { it.id }) { row ->
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
}

/** "Browse": a gradient tile per library, so an empty search is never a dead end. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowseRow(focus: FocusMemory, onBrowse: (BrowseTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(TvR.string.search_browse),
            style = Antresol.typography.sectionTitle,
            modifier = Modifier.padding(horizontal = ScreenPaddingH),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .trapHorizontalFocus()
                .focusRestorer(),
        ) {
            items(BrowseTarget.entries, key = { it.name }) { target ->
                BrowseTile(target, onClick = { onBrowse(target) }, modifier = Modifier.remembersFocus(focus, "browse/${target.name}"))
            }
        }
    }
}

@Composable
private fun BrowseTile(target: BrowseTarget, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (from, to) = AntresolGradients.avatarColors(target.gradient)
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 176.dp, height = 112.dp),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.card),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, AntresolColors.Label), shape = AntresolShapes.card),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.CARD_FOCUS_SCALE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AntresolGradients.cssLinearGradient(135f, from, to))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(target.label), style = Antresol.typography.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(target.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

private val BrowseTarget.label: Int
    get() = when (this) {
        BrowseTarget.Movies -> R.string.nav_movies
        BrowseTarget.Shows -> R.string.nav_tv_shows
        BrowseTarget.Music -> R.string.nav_music
        BrowseTarget.Books -> R.string.nav_books
        BrowseTarget.Audiobooks -> TvR.string.books_audiobooks
        BrowseTarget.Comics -> TvR.string.books_comics
    }

private val BrowseTarget.icon: ImageVector
    get() = when (this) {
        BrowseTarget.Movies -> TvIcons.Film
        BrowseTarget.Shows -> TvIcons.Tv
        BrowseTarget.Music -> TvIcons.MusicNote
        BrowseTarget.Books, BrowseTarget.Comics -> TvIcons.Book
        BrowseTarget.Audiobooks -> TvIcons.Headphones
    }

/** Gradient presets in the design's order: red, violet, pink, amber, green, cyan. */
private val BrowseTarget.gradient: String
    get() = when (this) {
        BrowseTarget.Movies -> "grad-3"
        BrowseTarget.Shows -> "grad-1"
        BrowseTarget.Music -> "grad-6"
        BrowseTarget.Books -> "grad-5"
        BrowseTarget.Audiobooks -> "grad-4"
        BrowseTarget.Comics -> "grad-7"
    }

private val FIELD_WIDTH = 720.dp

@Composable
private fun Placeholder(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(horizontal = ScreenPaddingH),
    ) { content() }
}
