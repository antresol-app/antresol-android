package app.antresol.ui.family

import app.antresol.core.api.ApiException
import app.antresol.core.model.FamilyMember
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyModelTest {
    private fun member(userId: Long, itemId: Long = 10, positionMs: Long = 0, finished: Boolean = false, next: Boolean = false) = FamilyMember(
        userId = userId, name = "Sam", itemId = itemId, positionMs = positionMs, finished = finished, next = next, updatedAt = "2026-11-25T18:00:00Z",
    )

    private fun TestScope.model(api: FakeFamilyApi) = FamilyModel(api, this)

    @Test
    fun theFamilyOfAPageIsReadOnceAndStaysEmptyForAKid() = runTest(StandardTestDispatcher()) {
        val api = FakeFamilyApi().apply { answers[10] = listOf(member(2, positionMs = 5_000)) }
        val family = model(api)
        family.load(10)
        runCurrent()
        assertEquals(listOf(2L), family.members.map { it.userId })
        // Another item and a server that lists nobody: nothing shown, nothing left of the first.
        family.load(11)
        assertEquals(emptyList<FamilyMember>(), family.members)
        runCurrent()
        assertEquals(emptyList<FamilyMember>(), family.members)
        assertEquals(listOf("members 10", "members 11"), api.calls)
    }

    @Test
    fun aFailedReadShowsNobodyAndThePageWorksWithoutIt() = runTest(StandardTestDispatcher()) {
        val api = FakeFamilyApi().apply { membersFailure = IOException("offline") }
        val family = model(api)
        family.load(10)
        runCurrent()
        assertEquals(emptyList<FamilyMember>(), family.members)
        assertFalse(family.failed)
    }

    @Test
    fun aReadAgainKeepsWhatIsShownUntilTheAnswer() = runTest(StandardTestDispatcher()) {
        val api = FakeFamilyApi().apply { answers[10] = listOf(member(2, positionMs = 5_000)) }
        val family = model(api)
        family.load(10)
        runCurrent()
        api.membersGate = CompletableDeferred()
        api.answers[10] = listOf(member(2, positionMs = 9_000))
        family.refresh()
        runCurrent()
        assertEquals(5_000L, family.members.single().positionMs)
        api.membersGate!!.complete(Unit)
        runCurrent()
        assertEquals(9_000L, family.members.single().positionMs)
    }

    @Test
    fun continuingFromAPositionCopiesItFirstThenOpensIt() = runTest(StandardTestDispatcher()) {
        val api = FakeFamilyApi().apply { adoptGate = CompletableDeferred() }
        val family = model(api)
        val opened = ArrayList<String>()
        val theirs = member(7, itemId = 42, positionMs = 2_000_000)
        family.jump(theirs) { opened += "open ${it.itemId} at ${it.positionMs}" }
        runCurrent()
        assertEquals(7L, family.busy)
        assertEquals("nothing opens before the copy is made", emptyList<String>(), opened)
        // A second press while the first is on its way is ignored.
        family.jump(member(8, itemId = 42, positionMs = 1_000)) { opened += "other" }
        api.answers[42] = listOf(theirs)
        api.adoptGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("adopt 42 7"), api.calls)
        assertEquals(listOf("open 42 at 2000000"), opened)
        assertEquals(null, family.busy)
    }

    @Test
    fun aNextEpisodeJustStartsWithoutCopyingAnything() = runTest(StandardTestDispatcher()) {
        val api = FakeFamilyApi()
        val family = model(api)
        var opened = false
        family.jump(member(3, itemId = 12, next = true)) { opened = true }
        runCurrent()
        assertTrue(opened)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun aRefusedCopyOpensNothingSaysSoAndReadsTheFamilyAgain() = runTest(StandardTestDispatcher()) {
        val api = FakeFamilyApi().apply { adoptFailure = ApiException(404, "Not found") }
        val family = model(api)
        family.load(42)
        runCurrent()
        var opened = false
        family.jump(member(7, itemId = 42, positionMs = 2_000)) { opened = true }
        runCurrent()
        assertFalse(opened)
        assertTrue(family.failed)
        assertEquals(null, family.busy)
        assertEquals(listOf("members 42", "adopt 42 7", "members 42"), api.calls)
        // The next try starts clean.
        api.adoptFailure = null
        api.answers[42] = listOf(member(7, itemId = 42, positionMs = 2_000))
        family.jump(member(7, itemId = 42, positionMs = 2_000)) { opened = true }
        assertFalse(family.failed)
        runCurrent()
        assertTrue(opened)
    }
}
