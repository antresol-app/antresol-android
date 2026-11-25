package app.antresol.mobile.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.core.model.Track
import app.antresol.core.requests.AskCards
import app.antresol.mobile.R
import app.antresol.mobile.playback.PlaybackConnection
import app.antresol.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.browse.GridSource
import app.antresol.mobile.ui.browse.OpenGrid
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassTextField
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PageTitle
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.SectionTitle
import app.antresol.mobile.ui.components.SegmentedPills
import app.antresol.mobile.ui.components.Shelf
import app.antresol.mobile.ui.components.TabHeader
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.mobile.ui.shell.NavTab
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.format.Durations
import app.antresol.ui.requests.RequestStatusPill
import app.antresol.ui.requests.TitleRequest
import app.antresol.ui.requests.requestKindLabel
import app.antresol.ui.requests.requestBadgeLabel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolShapes

/**
 * Search: field, kind filter pills, then results; with an empty query the colourful Browse tiles.
 * Opening the tab with an empty query focuses the field (keyboard up); scrolling hides the keyboard.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    playback: PlaybackConnection,
    onOpen: (Card) -> Unit,
    onTab: (NavTab) -> Unit,
    onGrid: OpenGrid,
    onNowPlaying: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val bottom = LocalBottomBarPadding.current
    val listState = rememberLazyListState()
    val fieldFocus = remember { FocusRequester() }
    val badge = askBadge()
    // Back from a title page: what the kid asked there shows on the results.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAsks()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (viewModel.query.isEmpty()) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (scrolling) focus.clearFocus() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(AntresolColors.Background),
        contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 16.dp),
    ) {
        item("top") { TabHeader() }
        item("title") { PageTitle(stringResource(UiR.string.nav_search)) }
        item("field") {
            GlassTextField(
                value = viewModel.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.search_field_hint),
                leadingIcon = AntresolIcons.Search,
                trailingIcon = if (viewModel.query.isNotEmpty()) MobileIcons.Close else null,
                trailingDescription = stringResource(R.string.search_clear),
                onTrailingClick = { viewModel.onQueryChange("") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier
                    .padding(horizontal = PageGutter, vertical = 8.dp)
                    .focusRequester(fieldFocus)
                    .testTag("search-field"),
            )
        }
        item("filters") {
            SegmentedPills(
                options = SearchFilter.entries,
                selected = viewModel.filter,
                label = { stringResource(it.label) },
                onSelect = viewModel::selectFilter,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        when (val results = viewModel.results) {
            null -> item("browse") { BrowseTiles(onTab = onTab, onGrid = onGrid) }
            LoadState.Loading -> item("loading") { LoadingView() }
            is LoadState.Failed -> item("error") { ErrorView(results.error, onRetry = viewModel::retry) }
            is LoadState.Ready -> {
                // The addons' sections follow the library's, as they arrive.
                val sections = results.value.sections(viewModel.filter) + viewModel.addonSections.sections(viewModel.filter)
                // A kid's titles to ask a parent for come last: «Find and Ask».
                val asks = AskCards.of(viewModel.askCandidates, badge)
                if (sections.isEmpty() && asks.isEmpty()) {
                    // An addon or the kid's catalogue may still be looking: that is not «no results» yet.
                    item("empty") {
                        when {
                            viewModel.addonsSearching || viewModel.asksSearching -> LoadingView()
                            viewModel.asksFailed -> EmptyView(stringResource(UiR.string.request_search_failed))
                            else -> EmptyView(stringResource(UiR.string.search_no_results, viewModel.query.trim()))
                        }
                    }
                }
                items(sections, key = { it.id }) { section ->
                    if (section.songs.isNotEmpty()) {
                        SongResults(
                            songs = section.songs,
                            onPlay = { index ->
                                if (playback.playTracks(section.songs, index) != null) onNowPlaying()
                            },
                            onOpen = onOpen,
                        )
                    } else {
                        Shelf(title = section.label ?: stringResource(section.titleRes()), cards = section.cards, onOpen = onOpen)
                    }
                }
                if (asks.isNotEmpty()) {
                    item("ask") {
                        Shelf(
                            title = stringResource(UiR.string.request_find_title),
                            cards = asks.map { it.card },
                            // A movie or series opens its title page, the rest a sheet to ask from.
                            onOpen = { card ->
                                val candidate = AskCards.candidateOf(asks, card)
                                if (candidate == null || candidate.isVideo) onOpen(card) else viewModel.openSheet(candidate)
                            },
                        )
                    }
                }
            }
        }
    }
    viewModel.sheetOf?.let { candidate -> AskSheet(candidate, viewModel.sheet, onDismiss = viewModel::closeSheet) }
}

/** The badge a title of the «Find and Ask» section carries: its request's state (none while it was not asked for). */
@Composable
private fun askBadge(): (KidRequest?) -> String? {
    val labels = listOf(
        RequestStatus.PENDING, RequestStatus.APPROVED, RequestStatus.IN_LIBRARY, RequestStatus.DECLINED,
    ).associateWith { stringResource(requestBadgeLabel(it)) }
    return { request -> request?.let { labels[it.status] } }
}

/**
 * The sheet of a result with no page of its own (an album, a book): what it is, and «Ask a Parent» or the state of
 * the request the kid made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(candidate: KidCandidate, request: TitleRequest?, onDismiss: () -> Unit) {
    val made = request?.request
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp).padding(bottom = 20.dp).testTag("ask-sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(candidate.title, style = Antresol.typography.sectionTitle)
            val line = listOfNotNull(requestKindLabel(candidate.kind), candidate.subtitle.takeIf { it.isNotBlank() }, candidate.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = Antresol.typography.caption)
            when {
                made != null -> RequestStatusPill(made)
                request?.canAsk == true -> PrimaryPillButton(
                    text = stringResource(if (request.busy) UiR.string.request_asking else UiR.string.request_ask),
                    icon = AntresolIcons.Send,
                    enabled = !request.busy,
                    onClick = { request.ask(candidate.ask()) },
                    modifier = Modifier.fillMaxWidth().testTag("request-ask"),
                )
                else -> request?.why?.let { Text(it, style = Antresol.typography.caption, modifier = Modifier.testTag("request-note")) }
            }
            if (made == null && request?.why == null) Text(stringResource(UiR.string.request_sheet_hint), style = Antresol.typography.caption)
            if (request?.failed == true) Text(stringResource(UiR.string.request_ask_failed), style = Antresol.typography.caption.copy(color = AntresolColors.Red))
        }
    }
}

private val SearchFilter.label: Int
    @StringRes get() = when (this) {
        SearchFilter.All -> R.string.search_filter_all
        SearchFilter.Movies -> UiR.string.nav_movies
        SearchFilter.Shows -> UiR.string.nav_tv_shows
        SearchFilter.Music -> UiR.string.nav_music
        SearchFilter.Books -> UiR.string.nav_books
    }

@StringRes
private fun SearchSection.titleRes(): Int = when (id) {
    SECTION_MOVIES -> UiR.string.nav_movies
    SECTION_SHOWS -> UiR.string.nav_tv_shows
    SECTION_ARTISTS -> UiR.string.search_section_artists
    SECTION_ALBUMS -> UiR.string.search_section_albums
    SECTION_AUDIOBOOKS -> UiR.string.search_section_audiobooks
    SECTION_BOOKS -> UiR.string.search_section_books
    SECTION_COMICS -> R.string.books_comics
    else -> UiR.string.search_section_library
}

/** Songs as compact rows; a tap plays the found songs from that one, a long press opens its album. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongResults(songs: List<Track>, onPlay: (index: Int) -> Unit, onOpen: (Card) -> Unit) {
    val urls = LocalMediaUrls.current
    Column(Modifier.padding(top = 20.dp)) {
        SectionTitle(stringResource(UiR.string.search_section_songs))
        songs.forEachIndexed { index, song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusClickable(
                        shape = AntresolShapes.control,
                        onLongClick = { onOpen(Card(id = song.albumId, kind = Kind.ALBUM, title = song.album, poster = song.cover)) },
                        onClick = { onPlay(index) },
                    )
                    .testTag("search-song-${song.id}")
                    .padding(horizontal = PageGutter, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtworkImage(url = urls?.image(song.cover), title = "", shape = AntresolShapes.badge, modifier = Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                        style = Antresol.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(Durations.clock(song.durationMs), style = Antresol.typography.caption)
            }
        }
    }
}

private data class BrowseTile(
    @StringRes val label: Int,
    val icon: ImageVector,
    val gradient: String,
    val onClick: () -> Unit,
)

/** The design's colourful category tiles: tabs for the libraries, grids for audiobooks and comics. */
@Composable
private fun BrowseTiles(onTab: (NavTab) -> Unit, onGrid: OpenGrid) {
    val audiobooks = stringResource(R.string.books_audiobooks)
    val comics = stringResource(R.string.books_comics)
    val tiles = listOf(
        BrowseTile(UiR.string.nav_movies, MobileIcons.Film, "grad-3") { onTab(NavTab.Movies) },
        BrowseTile(UiR.string.nav_tv_shows, MobileIcons.Tv, "grad-1") { onTab(NavTab.Shows) },
        BrowseTile(UiR.string.nav_music, MobileIcons.Music, "grad-6") { onTab(NavTab.Music) },
        BrowseTile(UiR.string.nav_books, MobileIcons.Book, "grad-5") { onTab(NavTab.Books) },
        BrowseTile(R.string.books_audiobooks, MobileIcons.Headphones, "grad-4") { onGrid(GridSource.Books(Kind.AUDIOBOOK), audiobooks) },
        BrowseTile(R.string.books_comics, MobileIcons.Book, "grad-7") { onGrid(GridSource.Books(Kind.COMIC), comics) },
    )
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
    Column(Modifier.padding(top = 12.dp)) {
        SectionTitle(stringResource(UiR.string.search_browse))
        Column(
            modifier = Modifier.padding(horizontal = PageGutter, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile -> BrowseTileView(tile, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Text(
            stringResource(UiR.string.search_prompt),
            style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
            modifier = Modifier.padding(horizontal = PageGutter, vertical = 24.dp),
        )
    }
}

@Composable
private fun BrowseTileView(tile: BrowseTile, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(112.dp)
            .clip(AntresolShapes.card)
            .background(AntresolGradients.avatar(tile.gradient))
            .focusClickable(AntresolShapes.card, onClick = tile.onClick)
            .padding(16.dp),
    ) {
        Text(
            stringResource(tile.label),
            style = Antresol.typography.sectionTitle,
            maxLines = 2,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Icon(tile.icon, contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.align(Alignment.BottomStart).size(20.dp))
    }
}
