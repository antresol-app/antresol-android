package app.antresol.tv.homechannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diff between the programs the system holds and the ones the server now asks for: add, update, remove. */
class HomeDiffTest {
    private fun spec(id: Long, rank: Int = 0, positionMs: Long = 0, slot: Slot = Slot.WatchNext) = ProgramSpec(
        key = "k$id", slot = slot, kind = ProgramKind.Movie, title = "T$id", artwork = ArtworkRef("/p/$id"), aspect = Aspect.Wide,
        link = HomeLink.Play(id, 1), rank = rank, positionMs = positionMs, durationMs = 100, posterUri = "content://a/$id",
        watchNext = WatchNextKind.Continue,
    )

    private fun stored(rowId: Long, spec: ProgramSpec) = StoredProgram(rowId, spec.key, spec.fingerprint())

    @Test
    fun nothingStoredAddsEverythingInOrder() {
        val plan = HomeDiff.plan(emptyList(), listOf(spec(1, 0), spec(2, 1)))
        assertEquals(listOf("k1", "k2"), plan.add.map { it.key })
        assertTrue(plan.update.isEmpty())
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun aProgramThatIsAlreadyThereAsItIsIsLeftAlone() {
        val a = spec(1)
        val plan = HomeDiff.plan(listOf(stored(10, a)), listOf(a))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun aChangedProgramIsUpdatedInPlaceByItsRowId() {
        val before = spec(1, positionMs = 10)
        val after = spec(1, positionMs = 50)
        val plan = HomeDiff.plan(listOf(stored(10, before)), listOf(after))
        assertEquals(listOf(10L to after), plan.update)
        assertTrue(plan.add.isEmpty())
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun aProgramTheServerNoLongerListsIsRemoved() {
        val a = spec(1)
        val b = spec(2, rank = 1)
        val plan = HomeDiff.plan(listOf(stored(10, a), stored(11, b)), listOf(a))
        assertEquals(listOf(11L), plan.remove)
        assertTrue(plan.add.isEmpty())
        assertTrue(plan.update.isEmpty())
    }

    @Test
    fun everythingGoesWhenNothingIsWanted() {
        val plan = HomeDiff.plan(listOf(stored(10, spec(1)), stored(11, spec(2))), emptyList())
        assertEquals(listOf(10L, 11L), plan.remove)
    }

    @Test
    fun rowsWithoutOurKeyAndDuplicateRowsAreRemovedAndOneRowOfAKeyIsKept() {
        val a = spec(1)
        val plan = HomeDiff.plan(
            listOf(StoredProgram(1, null, null), StoredProgram(2, "", 5), stored(3, a), stored(4, a)),
            listOf(a),
        )
        assertEquals(listOf(1L, 2L, 4L), plan.remove.sorted())
        assertTrue(plan.add.isEmpty())
        assertTrue(plan.update.isEmpty())
    }

    @Test
    fun aRowWithAnOldFingerprintFormatIsUpdated() {
        val a = spec(1)
        val plan = HomeDiff.plan(listOf(StoredProgram(10, a.key, null)), listOf(a))
        assertEquals(listOf(10L to a), plan.update)
    }

    @Test
    fun aWantedKeyListedTwiceIsAddedOnce() {
        val plan = HomeDiff.plan(emptyList(), listOf(spec(1), spec(1)))
        assertEquals(1, plan.add.size)
    }

    @Test
    fun aChangedListIsRewrittenWholeWhenItsOrderIsKeptByTime() {
        val a = spec(1, rank = 0)
        val b = spec(2, rank = 1)
        val c = spec(3, rank = 2)
        val rows = listOf(stored(10, a), stored(11, b), stored(12, c))
        val newB = spec(2, rank = 1, positionMs = 77)

        val plan = HomeDiff.plan(rows, listOf(a, newB, c), rewriteAllOnChange = true)
        assertEquals(listOf(10L, 11L, 12L), plan.update.map { it.first })
        assertEquals(listOf(a, newB, c), plan.update.map { it.second })

        // Nothing changed: nothing is rewritten either.
        assertTrue(HomeDiff.plan(rows, listOf(a, b, c), rewriteAllOnChange = true).isEmpty)
    }

    @Test
    fun aRemovalAloneDoesNotRewriteTheRest() {
        val a = spec(1, rank = 0)
        val b = spec(2, rank = 1)
        val plan = HomeDiff.plan(listOf(stored(10, a), stored(11, b)), listOf(a), rewriteAllOnChange = true)
        assertEquals(listOf(11L), plan.remove)
        assertTrue(plan.update.isEmpty())
    }

    @Test
    fun aNewTitleInFrontMovesTheOthersDownAndTheyAreUpdated() {
        val a = spec(1, rank = 0)
        val b = spec(2, rank = 1)
        val newcomer = spec(3, rank = 0)
        val plan = HomeDiff.plan(
            listOf(stored(10, a), stored(11, b)),
            listOf(newcomer, a.copy(rank = 1), b.copy(rank = 2)),
        )
        assertEquals(listOf("k3"), plan.add.map { it.key })
        assertEquals(setOf(10L, 11L), plan.update.map { it.first }.toSet())
    }
}
