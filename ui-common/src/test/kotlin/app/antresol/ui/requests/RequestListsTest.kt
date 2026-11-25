package app.antresol.ui.requests

import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.ui.LoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RequestListsTest {
    private fun request(id: Long, status: String = RequestStatus.PENDING, at: String = "2026-11-2${id}T10:00:00Z") =
        KidRequest(id = id, key = "tmdb:$id", kind = "movie", tmdbId = id, title = "Film $id", status = status, createdAt = at)

    @Test
    fun theKidsListIsNewestFirstAndAQuietReadKeepsWhatIsShownWhenItFails() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { mineList = listOf(request(1), request(3), request(2)) }
        val list = MyRequests(api, this)
        assertTrue(list.state is LoadState.Loading)
        list.load()
        runCurrent()
        assertEquals(listOf(3L, 2L, 1L), (list.state as LoadState.Ready).value.map { it.id })

        api.mineFailure = IOException("down")
        list.load(quiet = true)
        runCurrent()
        assertTrue("the list stays", list.state is LoadState.Ready)
        list.load()
        runCurrent()
        assertTrue("a loud read shows the error", list.state is LoadState.Failed)
    }

    @Test
    fun theAdministratorsListSplitsWaitingFromAnsweredAndAnswersOneAtATime() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply {
            allList = listOf(request(1), request(2, RequestStatus.DECLINED), request(3), request(4, RequestStatus.IN_LIBRARY))
        }
        val list = RequestsToAnswer(api, this)
        list.load()
        runCurrent()
        assertEquals(listOf(1L, 3L), list.waiting.map { it.id })
        assertEquals(listOf(2L, 4L), list.answered.map { it.id })

        list.approve(list.waiting.first())
        runCurrent()
        assertEquals(listOf("all", "approve 1", "all"), api.calls)
        assertEquals(listOf(3L), list.waiting.map { it.id })
        assertNull(list.busy)

        list.decline(list.waiting.single(), "  Not yet  ")
        runCurrent()
        assertEquals("decline 3 Not yet", api.calls.last { it.startsWith("decline") })
        assertTrue(list.waiting.isEmpty())
        assertEquals("Not yet", list.answered.first { it.id == 3L }.reason)
    }

    @Test
    fun aFailedAnswerIsReportedAndTheRequestStaysWaiting() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply {
            allList = listOf(request(1))
            answerFailure = FakeRequestsApi.conflict()
        }
        val list = RequestsToAnswer(api, this)
        list.load()
        runCurrent()
        list.approve(list.waiting.single())
        runCurrent()
        assertTrue(list.answerFailed)
        assertEquals(listOf(1L), list.waiting.map { it.id })
        assertNull(list.busy)
        // The next answer starts clean.
        api.answerFailure = null
        list.approve(list.waiting.single())
        runCurrent()
        assertFalse(list.answerFailed)
    }

    @Test
    fun theCountIsAHintThatKeepsItsLastNumberWhenAskingFails() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { pendingCount = 3 }
        val pending = PendingRequests(api, this)
        pending.refresh()
        runCurrent()
        assertEquals(3, pending.count)
        api.pendingFailure = IOException("down")
        pending.refresh()
        runCurrent()
        assertEquals(3, pending.count)
    }
}
