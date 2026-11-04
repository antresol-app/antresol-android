package app.antresol.tv.ui.home

import app.antresol.core.model.Card
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.Shelf
import app.antresol.core.model.ShelfId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContentTest {
    private val movie = Card(id = 19, kind = "movie", title = "Big Buck Demo", backdrop = "/b")
    private val album = Card(id = 14, kind = "album", title = "Sample Record")
    private val episode = Card(id = 12, kind = "episode", title = "Episode 1")

    @Test
    fun keepsServerHeroAndDropsEmptyShelves() {
        val content = HomeContent.from(
            HomeResponse(
                hero = listOf(movie),
                shelves = listOf(
                    Shelf(ShelfId.CONTINUE, "Continue Watching", listOf(episode)),
                    Shelf(ShelfId.WATCHLIST, "Want to Watch", emptyList()),
                ),
            ),
        )
        assertEquals(listOf(movie), content.hero)
        assertNull(content.heroSource)
        assertEquals(listOf(ShelfId.CONTINUE), content.shelves.map { it.id })
    }

    @Test
    fun borrowsHeroFromVideoShelfWhenServerSendsNone() {
        val content = HomeContent.from(
            HomeResponse(
                shelves = listOf(
                    Shelf(ShelfId.RECENTLY_PLAYED, "Recently Played", listOf(album)),
                    Shelf(ShelfId.RECENT_MOVIES, "Recently Added Movies", listOf(movie)),
                ),
            ),
        )
        assertEquals(listOf(movie), content.hero)
        assertEquals("Recently Added Movies", content.heroSource)
    }

    @Test
    fun emptyLibrary() {
        val content = HomeContent.from(HomeResponse(shelves = listOf(Shelf(ShelfId.CONTINUE, "Continue Watching"))))
        assertTrue(content.isEmpty)
    }
}
