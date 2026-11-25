package app.antresol.core.family

import app.antresol.core.model.FamilyMember
import app.antresol.core.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring, the copy of a position and whether there is somewhere to continue (the web's FamilyProgress rules). */
class FamilyRulesTest {
    private fun member(
        userId: Long = 2,
        positionMs: Long = 0,
        fraction: Double = 0.0,
        finished: Boolean = false,
        next: Boolean = false,
        locator: String = "",
        page: Int = 0,
        done: Int = 0,
        total: Int = 0,
    ) = FamilyMember(
        userId = userId, name = "Sam", itemId = 10, positionMs = positionMs, fraction = fraction, finished = finished, next = next,
        locator = locator, page = page, done = done, total = total, updatedAt = "2026-11-25T18:00:00Z",
    )

    @Test
    fun theRingIsTheSharesOfASeriesElseOfTheItem() {
        assertEquals(0.5f, Family.ring(member(fraction = 0.3, done = 2, total = 4)), 0f)
        assertEquals(0.3f, Family.ring(member(fraction = 0.3)), 0.0001f)
        assertEquals(1f, Family.ring(member(finished = true, fraction = 0.1, done = 1, total = 4)), 0f)
        assertEquals(1f, Family.ring(member(fraction = 1.7)), 0f)
        assertEquals(0f, Family.ring(member(fraction = -1.0)), 0f)
    }

    @Test
    fun onlyARealPositionIsTakenAsOwn() {
        assertTrue(Family.adopts(member(positionMs = 5_000)))
        assertTrue(Family.adopts(member(locator = "page:12")))
        // A next episode just starts; a finished one and an unstarted one have nothing to take.
        assertFalse(Family.adopts(member(next = true)))
        assertFalse(Family.adopts(member(positionMs = 5_000, finished = true)))
        assertFalse(Family.adopts(member()))
    }

    @Test
    fun aFilmContinuesOnlyFromAPositionAndASeriesAlways() {
        assertFalse(Family.canContinue(FamilyKind.Movie, member()))
        assertTrue(Family.canContinue(FamilyKind.Movie, member(positionMs = 1)))
        assertFalse(Family.canContinue(FamilyKind.Episode, member(positionMs = 9_000, finished = true)))
        assertTrue(Family.canContinue(FamilyKind.Series, member(next = true)))
        assertTrue(Family.canContinue(FamilyKind.Series, member(positionMs = 9_000)))
        assertFalse(Family.canContinue(FamilyKind.Series, member(finished = true)))
        assertTrue(Family.canContinue(FamilyKind.Comic, member(next = true)))
        assertFalse(Family.canContinue(FamilyKind.Audiobook, member()))
        assertTrue(Family.canContinue(FamilyKind.Audiobook, member(positionMs = 60_000)))
        assertFalse(Family.canContinue(FamilyKind.Book, member()))
        assertTrue(Family.canContinue(FamilyKind.Book, member(page = 5)))
        assertTrue(Family.canContinue(FamilyKind.Book, member(locator = "epubcfi(/6/4)")))
    }

    @Test
    fun theFirstWhoCanBeContinuedFromSkipsTheFinishedOnes() {
        val done = member(userId = 1, finished = true)
        val sam = member(userId = 2, positionMs = 4_000)
        assertEquals(2L, Family.firstToContinue(FamilyKind.Movie, listOf(done, sam))?.userId)
        assertNull(Family.firstToContinue(FamilyKind.Movie, listOf(done)))
        assertNull(Family.firstToContinue(FamilyKind.Movie, emptyList()))
    }

    @Test
    fun aPageIsAFamilyKindOnlyWhenItHasProgressToShare() {
        assertEquals(FamilyKind.Series, FamilyKind.of(Kind.SEASON))
        assertEquals(FamilyKind.Series, FamilyKind.of(Kind.SERIES))
        assertEquals(FamilyKind.Comic, FamilyKind.of(Kind.COMIC_VOLUME))
        assertEquals(FamilyKind.Audiobook, FamilyKind.of(Kind.AUDIOBOOK))
        assertNull(FamilyKind.of(Kind.ALBUM))
        assertNull(FamilyKind.of(Kind.PLAYLIST))
    }
}
