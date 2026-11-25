package app.antresol.core.requests

import app.antresol.core.model.Card
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.KidRequestState
import app.antresol.core.model.Kind
import app.antresol.core.model.RequestSearchKind
import app.antresol.core.model.RequestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskCardsTest {
    private fun movie(tmdb: Long, request: KidRequest? = null) = KidCandidate(
        kind = "movie", tmdbId = tmdb, title = "Film $tmdb", year = 2020, poster = "https://image.example/$tmdb.jpg",
        kidRequest = KidRequestState("tmdb:$tmdb", request = request),
    )

    private fun album(ext: String, request: KidRequest? = null) = KidCandidate(
        kind = "music", extId = ext, title = "Album $ext", subtitle = "Artist", year = 1988, aliases = listOf("Other"),
        kidRequest = KidRequestState("ext:$ext", request = request),
    )

    private fun asked(status: String, libraryItem: Long? = null) = KidRequest(id = 4, key = "tmdb:1", kind = "movie", tmdbId = 1, status = status, libraryItemId = libraryItem)

    @Test
    fun aMovieOffTheLibraryOpensItsTitlePageByItsTmdbId() {
        val card = AskCards.of(listOf(movie(603)), { null }).single().card
        assertEquals(0L, card.id)
        assertEquals(603L, card.tmdbId)
        assertTrue(card.opensTitlePage)
        assertFalse(card.onServer)
        assertEquals("https://image.example/603.jpg", card.poster)
        assertTrue(card.badges.isEmpty())
    }

    @Test
    fun theBadgeIsTheStateOfTheRequestTheKidMade() {
        val states = mutableListOf<KidRequest?>()
        val cards = AskCards.of(listOf(movie(1), movie(2, asked(RequestStatus.PENDING))), { request ->
            states += request
            request?.status
        })
        assertEquals(listOf(null, RequestStatus.PENDING), cards.map { it.card.badges.singleOrNull() })
        assertEquals(2, states.size)
    }

    @Test
    fun aTitleTheLibraryHasNowOpensTheLibrarysOwnPage() {
        val card = AskCards.of(listOf(movie(1, asked(RequestStatus.IN_LIBRARY, libraryItem = 42))), { "In library" }).single().card
        assertEquals(42L, card.id)
        assertEquals(Kind.MOVIE, card.kind)
        assertTrue(card.onServer)
        assertFalse(card.opensTitlePage)
    }

    @Test
    fun anAlbumHasNoPageSoItsCardIsMadeUniqueAndTheCandidateIsFoundBackByIt() {
        val cards = AskCards.of(listOf(album("a"), album("b"), movie(5)), { null })
        val (first, second) = cards
        assertEquals(Kind.ALBUM, first.card.kind)
        assertEquals("Artist", first.card.subtitle)
        assertTrue("an album never opens a page", !first.card.opensTitlePage && first.card.id < 0)
        assertTrue("two albums keep two keys", first.card.id != second.card.id)
        assertEquals("ext:b", AskCards.candidateOf(cards, second.card)?.kidRequest?.key)
        assertNull(AskCards.candidateOf(cards, Card(id = 99, title = "Not here")))
    }

    @Test
    fun theSearchKindsAFilterShows() {
        assertEquals(listOf(RequestSearchKind.VIDEO), AskCards.searchKinds(video = true))
        assertEquals(listOf(RequestSearchKind.MUSIC), AskCards.searchKinds(music = true))
        assertEquals(listOf("book", "audiobook", "comic"), AskCards.searchKinds(books = true))
        assertTrue(AskCards.searchKinds().isEmpty())
    }

    @Test
    fun theAskBodyOfAMovieHoldsItsTmdbIdAndOfAnAlbumTheCatalogueFields() {
        val movie = movie(603).ask()
        assertEquals(603L, movie.tmdbId)
        assertEquals("", movie.extId)
        val album = album("mb-1").ask()
        assertEquals("mb-1", album.extId)
        assertEquals("Artist", album.subtitle)
        assertEquals(listOf("Other"), album.aliases)
        // Manga has no author in the request, as on the web.
        assertEquals("", KidCandidate(kind = "comic", extId = "c", title = "T", subtitle = "Someone").ask().subtitle)
    }

    @Test
    fun aRequestIsFoundByItsKindAndKey() {
        assertEquals("movie|tmdb:1", asked(RequestStatus.PENDING).slot)
        assertEquals(asked(RequestStatus.PENDING).slot, movie(1).slot)
        assertEquals("music|ext:a", album("a").slot)
    }
}
