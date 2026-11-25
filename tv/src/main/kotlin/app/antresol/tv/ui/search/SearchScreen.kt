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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.core.requests.AskCards
import app.antresol.tv.R as TvR
import app.antresol.ui.LoadState
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.FocusMemory
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.OptionsPanel
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
import app.antresol.ui.playback.MenuOption
import app.antresol.ui.requests.TitleRequest
import app.antresol.ui.requests.requestStatusLabel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

private const val FIELD_KEY = "search/field"

/** The Browse tiles under an empty search: where each one leads. */
enum class BrowseTarget { Movies, Shows, Music }

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
    /** A side panel (a kid's sheet to ask from) opened or closed: the shell hides its nav meanwhile. */
    onPanelOpenChanged: (Boolean) -> Unit = {},
) {
    val focus = rememberFocusMemory()
    LaunchedEffect(viewModel.sheetOf != null) { onPanelOpenChanged(viewModel.sheetOf != null) }
    // Back from a title page: what the kid asked there shows on the results.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAsks() }
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    LaunchedEffect(Unit) { focus.restore(FIELD_KEY, canTakeFocus) }
    val badge = askBadge()
    val scopeLabels = listOf(
        stringResource(TvR.string.search_scope_all),
        stringResource(R.string.nav_movies),
        stringResource(R.string.nav_tv_shows),
        stringResource(R.string.nav_music),
    )

    VerticalShelfScrolling {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 84.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Title, field and scope pills start on the page gutter like the result rows below them.
            item(key = "field") {
                Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPaddingH)) {
                    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.nav_search), style = Antresol.typography.pageTitle)
                        TvTextField(
                            value = viewModel.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = stringResource(TvR.string.search_tv_hint),
                            onSubmit = {},
                            imeAction = ImeAction.Search,
                            leadingIcon = AntresolIcons.Search,
                            modifier = Modifier
                                .fillMaxWidth()
                                .remembersFocus(focus, FIELD_KEY),
                        )
                        SegmentedPills(
                            options = scopeLabels,
                            selectedIndex = viewModel.scope.ordinal,
                            onSelect = { viewModel.selectScope(SearchScope.entries[it]) },
                            modifier = Modifier.remembersGroupFocus(focus, "search/scope"),
                        )
                    }
                }
            }
            when (val results = viewModel.results) {
                null -> {
                    item(key = "browse") { BrowseRow(focus, onBrowse) }
                    item(key = "prompt") {
                        Text(
                            stringResource(TvR.string.search_tv_prompt),
                            style = Antresol.typography.body,
                            color = AntresolColors.LabelSecondary,
                            modifier = Modifier.padding(horizontal = ScreenPaddingH),
                        )
                    }
                }
                LoadState.Loading -> item(key = "loading") { Placeholder { LoadingView() } }
                is LoadState.Failed -> item(key = "error") { Placeholder { ErrorView(results.error, onRetry = viewModel::retry, takeFocus = false) } }
                is LoadState.Ready -> {
                    // The addons' rows follow the library's, as they arrive.
                    val rows = viewModel.scope.filter(results.value) + viewModel.scope.filterAddons(viewModel.addonRows)
                    // A kid's titles to ask a parent for come last: «Find and Ask».
                    val asks = AskCards.of(viewModel.askCandidates, badge)
                    if (rows.isEmpty() && asks.isEmpty()) {
                        // An addon or the kid's catalogue may still be looking: that is not «no results» yet.
                        item(key = "empty") {
                            Placeholder {
                                when {
                                    viewModel.addonsSearching || viewModel.asksSearching -> LoadingView()
                                    viewModel.asksFailed -> MessageView(stringResource(R.string.request_search_failed))
                                    else -> MessageView(stringResource(R.string.search_no_results, viewModel.query))
                                }
                            }
                        }
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
                        if (asks.isNotEmpty()) {
                            item(key = ASK_ROW) {
                                ShelfRow(
                                    shelfId = ASK_ROW,
                                    title = stringResource(R.string.request_find_title),
                                    cards = asks.map { it.card },
                                    // A movie or series opens its title page, the rest a sheet to ask from.
                                    onOpen = { card ->
                                        val candidate = AskCards.candidateOf(asks, card)
                                        if (candidate == null || candidate.isVideo) onOpen(card) else viewModel.openSheet(candidate)
                                    },
                                    focus = focus,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    viewModel.sheetOf?.let { candidate -> AskSheet(candidate, viewModel.sheet, onClose = viewModel::closeSheet) }
}

/** The row of titles to ask a parent for. */
private const val ASK_ROW = "ask"

/** The badge a title of the «Find and Ask» row carries: its request's state (none while it was not asked for). */
@Composable
private fun askBadge(): (KidRequest?) -> String? {
    val labels = listOf(
        RequestStatus.PENDING, RequestStatus.APPROVED, RequestStatus.IN_LIBRARY, RequestStatus.DECLINED,
    ).associateWith { stringResource(requestStatusLabel(it)) }
    return { request -> request?.let { labels[it.status] } }
}

/**
 * The sheet of a result with no page of its own (an album, a book): what it is, and «Ask a Parent» or the state of
 * the request the kid made. OK on the button asks; Back closes.
 */
@Composable
private fun AskSheet(candidate: KidCandidate, request: TitleRequest?, onClose: () -> Unit) {
    val made = request?.request
    val line = listOf(candidate.subtitle, candidate.year.takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    val note = listOfNotNull(
        line.ifBlank { null },
        request?.why,
        stringResource(R.string.request_ask_failed).takeIf { request?.failed == true },
        stringResource(R.string.request_sheet_hint).takeIf { made == null && request?.why == null },
    ).joinToString("\n")
    OptionsPanel(
        title = candidate.title,
        note = note,
        options = when {
            made != null -> listOf(MenuOption(stringResource(requestStatusLabel(made.status)), selected = false) {})
            request?.canAsk == true -> listOf(MenuOption(stringResource(R.string.request_ask), selected = false) { request.ask(candidate.ask()) })
            else -> listOf(MenuOption(stringResource(R.string.action_cancel), selected = false) {})
        },
        // the panel stays open while the request is made: its answer replaces the button by the state
        closeOnSelect = false,
        onDismiss = onClose,
        noteLines = 4,
    )
}

/** "Browse": a gradient tile per library, so an empty search is never a dead end. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowseRow(focus: FocusMemory, onBrowse: (BrowseTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.search_browse),
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
    }

private val BrowseTarget.icon: ImageVector
    get() = when (this) {
        BrowseTarget.Movies -> TvIcons.Film
        BrowseTarget.Shows -> TvIcons.Tv
        BrowseTarget.Music -> TvIcons.MusicNote
    }

/** Gradient presets in the design's order: red, violet, pink. */
private val BrowseTarget.gradient: String
    get() = when (this) {
        BrowseTarget.Movies -> "grad-3"
        BrowseTarget.Shows -> "grad-1"
        BrowseTarget.Music -> "grad-6"
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
