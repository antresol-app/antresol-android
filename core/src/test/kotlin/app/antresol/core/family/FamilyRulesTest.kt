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

    // The resume prompt's candidates: who is somewhere else in what starts.

    private val now = java.time.Instant.parse("2026-11-25T20:00:00Z")

    private fun at(minutesAgo: Long, userId: Long = 2, itemId: Long = 10, positionMs: Long = 40 * 60_000, finished: Boolean = false, next: Boolean = false) =
        FamilyMember(
            userId = userId, name = "User$userId", itemId = itemId, positionMs = positionMs, finished = finished, next = next,
            updatedAt = now.minusSeconds(minutesAgo * 60).toString(),
        )

    @Test
    fun aPlaceOverTwoMinutesAwayInTheSameItemIsOffered() {
        val own = 34 * 60_000L
        assertEquals(listOf(2L), Family.resumeCandidates(listOf(at(5, positionMs = own + 121_000)), 10, own).map { it.userId })
        assertEquals(listOf(2L), Family.resumeCandidates(listOf(at(5, positionMs = own - 121_000)), 10, own).map { it.userId })
        // Two minutes exactly, and anything nearer, is the same place.
        assertEquals(emptyList<Long>(), Family.resumeCandidates(listOf(at(5, positionMs = own + 120_000)), 10, own).map { it.userId })
        assertEquals(emptyList<Long>(), Family.resumeCandidates(listOf(at(5, positionMs = own)), 10, own).map { it.userId })
    }

    @Test
    fun anotherEpisodeAlwaysCountsWhateverThePosition() {
        // The same position in the second hour of one item and in another episode: only the other episode is a different place.
        val own = 40 * 60_000L
        val same = at(5, userId = 2, itemId = 10, positionMs = own)
        val other = at(9, userId = 3, itemId = 11, positionMs = own)
        assertEquals(listOf(3L), Family.resumeCandidates(listOf(same, other), 10, own).map { it.userId })
    }

    @Test
    fun aFirstTimeComparesWithZero() {
        // No place of one's own: anybody a little way in has somewhere to take.
        assertEquals(listOf(2L), Family.resumeCandidates(listOf(at(5, positionMs = 121_000)), 10, 0).map { it.userId })
        assertEquals(emptyList<Long>(), Family.resumeCandidates(listOf(at(5, positionMs = 90_000)), 10, 0).map { it.userId })
    }

    @Test
    fun theFinishedTheNextAndTheUnstartedAreNotOffered() {
        val members = listOf(
            at(1, userId = 2, finished = true),
            at(2, userId = 3, next = true, positionMs = 0),
            at(3, userId = 4, next = true, itemId = 11),
            at(4, userId = 5, positionMs = 0, itemId = 11),
            at(5, userId = 6, itemId = 11),
        )
        assertEquals(listOf(6L), Family.resumeCandidates(members, 10, 0).map { it.userId })
    }

    @Test
    fun theMostRecentThreeAreOfferedNewestFirstWhateverTheServerOrder() {
        val members = listOf(at(300, userId = 2), at(10, userId = 3), at(60, userId = 4), at(5, userId = 5), at(1500, userId = 6))
        assertEquals(listOf(5L, 3L, 4L), Family.resumeCandidates(members, 11, 0).map { it.userId })
        assertEquals(listOf(5L), Family.resumeCandidates(members, 11, 0, limit = 1).map { it.userId })
        // RFC 3339 with an offset is compared as a time, not as text.
        val offset = at(0, userId = 7).copy(updatedAt = "2026-11-25T21:30:00+02:00") // 19:30Z
        val plain = at(0, userId = 8).copy(updatedAt = "2026-11-25T19:00:00Z")
        assertEquals(listOf(7L, 8L), Family.resumeCandidates(listOf(plain, offset), 11, 0).map { it.userId })
        assertEquals(emptyList<Long>(), Family.resumeCandidates(emptyList(), 11, 0).map { it.userId })
    }
}
