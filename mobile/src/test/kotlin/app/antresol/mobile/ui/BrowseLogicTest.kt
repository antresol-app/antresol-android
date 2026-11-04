package app.antresol.mobile.ui

import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.SearchResults
import app.antresol.core.model.ShelfId
import app.antresol.core.model.Track
import app.antresol.mobile.ui.browse.GridSource
import app.antresol.mobile.ui.browse.ShelfPage
import app.antresol.mobile.ui.search.SECTION_ARTISTS
import app.antresol.mobile.ui.search.SECTION_LIBRARY
import app.antresol.mobile.ui.search.SECTION_MOVIES
import app.antresol.mobile.ui.search.SECTION_SHOWS
import app.antresol.mobile.ui.search.SECTION_SONGS
import app.antresol.mobile.ui.search.SearchFilter
import app.antresol.mobile.ui.search.sections
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseLogicTest {
    @Test
    fun gridSourcesRoundTripThroughRoutes() {
        val sources = listOf(
            GridSource.Items(Kind.MOVIE),
            GridSource.Albums(),
            GridSource.Albums("Rock | Pop"),
            GridSource.Artists,
            GridSource.Playlists,
            GridSource.Books(Kind.COMIC),
            GridSource.ShelfCards(ShelfPage.Home, ShelfId.CONTINUE),
        )
        sources.forEach { assertEquals(it, GridSource.decode(it.encode())) }
    }

    @Test
    fun shelfChevronsOpenTheMatchingLibrary() {
        assertEquals(GridSource.Items(Kind.SERIES), GridSource.forShelf(ShelfPage.Home, ShelfId.RECENT_SERIES))
        assertEquals(GridSource.Books(Kind.AUDIOBOOK), GridSource.forShelf(ShelfPage.Books, ShelfId.AUDIOBOOKS))
        assertEquals(GridSource.ShelfCards(ShelfPage.Home, ShelfId.WATCHLIST), GridSource.forShelf(ShelfPage.Home, ShelfId.WATCHLIST))
    }

    @Test
    fun searchFiltersRegroupResults() {
        val results = SearchResults(
            library = listOf(Card(1, Kind.MOVIE, "Film"), Card(2, Kind.SERIES, "Show")),
            artists = listOf(Card(3, Kind.ARTIST, "Band")),
            songs = listOf(Track(4, "Song")),
        )
        assertEquals(listOf(SECTION_LIBRARY, SECTION_ARTISTS, SECTION_SONGS), results.sections(SearchFilter.All).map { it.id })
        assertEquals(listOf(1L), results.sections(SearchFilter.Movies).single { it.id == SECTION_MOVIES }.cards.map { it.id })
        assertEquals(listOf(2L), results.sections(SearchFilter.Shows).single { it.id == SECTION_SHOWS }.cards.map { it.id })
        assertEquals(listOf(SECTION_ARTISTS, SECTION_SONGS), results.sections(SearchFilter.Music).map { it.id })
        assertEquals(emptyList<String>(), results.sections(SearchFilter.Books).map { it.id })
    }
}
