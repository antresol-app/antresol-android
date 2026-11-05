package app.antresol.mobile.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.Card
import app.antresol.core.model.Genre
import app.antresol.core.model.Kind
import app.antresol.core.model.Shelf
import app.antresol.mobile.R
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LoadViewModel
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.LinkRow
import app.antresol.mobile.ui.music.ShuffleAllButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PageTitle
import app.antresol.mobile.ui.components.RefreshBox
import app.antresol.mobile.ui.components.SectionTitle
import app.antresol.mobile.ui.components.Shelf
import app.antresol.mobile.ui.components.TabHeader
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import app.antresol.ui.R as UiR

/** The Music tab's data: `/api/music/home` shelves and the genre list. */
data class MusicHome(val shelves: List<Shelf>, val genres: List<Genre>)

/** Opens a grid page. */
typealias OpenGrid = (source: GridSource, title: String) -> Unit

/** Music: Shuffle All, links to every album, song, artist and playlist, genre chips, then the music shelves. */
@Composable
fun MusicScreen(
    viewModel: LoadViewModel<MusicHome>,
    onOpen: (Card) -> Unit,
    onGrid: OpenGrid,
    onSongs: (title: String) -> Unit,
    onShuffleAll: () -> Unit,
) {
    val allAlbums = stringResource(R.string.music_all_albums)
    val songs = stringResource(R.string.music_songs)
    val artists = stringResource(R.string.music_artists)
    val playlists = stringResource(R.string.music_playlists)
    ShelvesPage(
        title = stringResource(UiR.string.nav_music),
        viewModel = viewModel,
        header = { ShuffleAllButton(onShuffleAll) },
        links = listOf(
            allAlbums to { onGrid(GridSource.Albums(), allAlbums) },
            songs to { onSongs(songs) },
            artists to { onGrid(GridSource.Artists, artists) },
            playlists to { onGrid(GridSource.Playlists, playlists) },
        ),
        shelves = { it.shelves },
        extra = { music ->
            if (music.genres.isNotEmpty()) {
                item("genres") {
                    SectionTitle(stringResource(R.string.music_genres), Modifier.padding(top = 12.dp))
                    GenreChips(music.genres) { genre -> onGrid(GridSource.Albums(genre.name), genre.name) }
                }
            }
        },
        onOpen = onOpen,
        onSeeAll = { shelf -> onGrid(GridSource.forShelf(ShelfPage.Music, shelf.id), shelf.title) },
    )
}

/** Books: links to all books, audiobooks and comics, then the books shelves. */
@Composable
fun BooksScreen(
    viewModel: LoadViewModel<List<Shelf>>,
    onOpen: (Card) -> Unit,
    onGrid: OpenGrid,
) {
    val allBooks = stringResource(R.string.books_all)
    val audiobooks = stringResource(R.string.books_audiobooks)
    val comics = stringResource(R.string.books_comics)
    ShelvesPage(
        title = stringResource(UiR.string.nav_books),
        viewModel = viewModel,
        links = listOf(
            allBooks to { onGrid(GridSource.Books(Kind.BOOK), allBooks) },
            audiobooks to { onGrid(GridSource.Books(Kind.AUDIOBOOK), audiobooks) },
            comics to { onGrid(GridSource.Books(Kind.COMIC), comics) },
        ),
        shelves = { it },
        extra = {},
        onOpen = onOpen,
        onSeeAll = { shelf -> onGrid(GridSource.forShelf(ShelfPage.Books, shelf.id), shelf.title) },
    )
}

/** A tab page of shelves: title, [header] (shown before the data loads), links, then the shelves. */
@Composable
private fun <T> ShelvesPage(
    title: String,
    viewModel: LoadViewModel<T>,
    links: List<Pair<String, () -> Unit>>,
    shelves: (T) -> List<Shelf>,
    extra: LazyListScope.(T) -> Unit,
    onOpen: (Card) -> Unit,
    onSeeAll: (Shelf) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val bottom = LocalBottomBarPadding.current
    RefreshBox(viewModel.refreshing, onRefresh = { viewModel.refresh(force = true) }, Modifier.fillMaxSize().background(AntresolColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 16.dp),
        ) {
            item("top") { TabHeader() }
            item("title") { PageTitle(title) }
            if (header != null) item("header") { header() }
            item("links") { LinkRow(links) }
            when (val state = viewModel.state) {
                LoadState.Loading -> item("loading") { LoadingView() }
                is LoadState.Failed -> item("error") { ErrorView(state.error, onRetry = viewModel::reload) }
                is LoadState.Ready -> {
                    extra(state.value)
                    val list = shelves(state.value).filter { it.cards.isNotEmpty() }
                    if (list.isEmpty()) item("empty") { EmptyView(stringResource(UiR.string.state_empty)) }
                    items(list, key = { it.id }) { shelf ->
                        Shelf(title = shelf.title, cards = shelf.cards, onOpen = onOpen, onSeeAll = { onSeeAll(shelf) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChips(genres: List<Genre>, onSelect: (Genre) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        items(genres, key = { it.name }) { genre ->
            Text(
                text = genre.name,
                style = Antresol.typography.bodyEmphasis,
                modifier = Modifier
                    .clip(AntresolShapes.pill)
                    .background(AntresolColors.Glass)
                    .border(1.dp, AntresolColors.GlassBorder, AntresolShapes.pill)
                    .clickable { onSelect(genre) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
