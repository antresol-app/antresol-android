package app.antresol.ui.requests

import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.KidRequestState
import app.antresol.core.model.RequestStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class KidAskSearchTest {
    private fun movie(tmdb: Long, request: KidRequest? = null) =
        KidCandidate(kind = "movie", tmdbId = tmdb, title = "Film $tmdb", kidRequest = KidRequestState("tmdb:$tmdb", request = request))

    private fun album(ext: String) = KidCandidate(kind = "music", extId = ext, title = "Album $ext", kidRequest = KidRequestState("ext:$ext"))

    private fun TestScope.search(api: FakeRequestsApi) = KidAskSearch(api, this)

    @Test
    fun aQueryIsDebouncedAndSearchingLastsUntilEveryAskedKindAnswered() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply {
            found["video"] = listOf(movie(1), movie(2))
            found["music"] = listOf(album("a"))
        }
        val search = search(api)
        search.start("far", listOf("video", "music"), debounceMs = 300)
        assertTrue("busy from the moment it is asked for", search.searching)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(api.calls.isEmpty())

        api.gate("music")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("search video far", "search music far"), api.calls)
        assertEquals("video answered", listOf(1L, 2L), search.candidates(listOf("video")).map { it.tmdbId })
        assertTrue("music is still looking", search.searching)
        api.gate("music").complete(Unit)
        runCurrent()
        assertFalse(search.searching)
        // The page's kinds, in their order: the albums first when the page says so.
        assertEquals(listOf("a", ""), search.candidates(listOf("music", "video")).map { it.extId }.take(2))
    }

    @Test
    fun typingAgainStartsOverAndAnOlderAnswerIsDropped() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { found["video"] = listOf(movie(1)) }
        val search = search(api)
        api.gate("video")
        search.start("fa", listOf("video"), debounceMs = 100)
        advanceTimeBy(150)
        runCurrent()
        search.start("far", listOf("video"), debounceMs = 100)
        api.gate("video").complete(Unit)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("search video fa", "search video far"), api.calls)
        assertFalse(search.searching)
        assertEquals(1, search.candidates(listOf("video")).size)
    }

    @Test
    fun aFilterThatShowsAnotherKindAsksForItAloneAndNotAgainForTheOnesAsked() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply {
            found["video"] = listOf(movie(1))
            found["music"] = listOf(album("a"))
        }
        val search = search(api)
        search.start("far", listOf("video"), debounceMs = 0)
        runCurrent()
        search.want(listOf("music"))
        runCurrent()
        search.want(listOf("video", "music"))
        runCurrent()
        assertEquals(listOf("search video far", "search music far"), api.calls)
    }

    @Test
    fun aKindThatFailsAddsNothingAndSaysSoWhileTheOthersStillCount() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply {
            found["music"] = listOf(album("a"))
            searchFailures["video"] = IOException("down")
        }
        val search = search(api)
        search.start("far", listOf("video", "music"), debounceMs = 0)
        runCurrent()
        assertFalse(search.searching)
        assertTrue(search.failed)
        assertEquals(listOf("a"), search.candidates(listOf("video", "music")).map { it.extId })
    }

    @Test
    fun clearDropsEverythingAndABlankQueryAsksNothing() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { found["video"] = listOf(movie(1)) }
        val search = search(api)
        search.start("far", listOf("video"), debounceMs = 0)
        runCurrent()
        search.clear()
        assertTrue(search.candidates(listOf("video")).isEmpty())
        assertFalse(search.searching)
        search.want(listOf("video"))
        runCurrent()
        assertEquals("nothing is asked without a query", 1, api.calls.size)
    }

    @Test
    fun aRequestTheKidMadeShowsOnItsResultsAndARefreshReadsAgainInPlace() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { found["video"] = listOf(movie(1), movie(2)) }
        val search = search(api)
        search.start("far", listOf("video"), debounceMs = 0)
        runCurrent()
        val made = KidRequest(id = 9, key = "tmdb:2", kind = "movie", tmdbId = 2, status = RequestStatus.PENDING)
        search.put(made)
        val shown = search.candidates(listOf("video"))
        assertNull(shown[0].kidRequest?.request)
        assertEquals(RequestStatus.PENDING, shown[1].kidRequest?.request?.status)

        // A parent answered meanwhile: the page asks again and the old results stay until the new ones are in.
        api.found["video"] = listOf(movie(1), movie(2, made.copy(status = RequestStatus.APPROVED)))
        search.refresh()
        assertEquals(RequestStatus.PENDING, search.candidates(listOf("video"))[1].kidRequest?.request?.status)
        runCurrent()
        assertEquals(RequestStatus.APPROVED, search.candidates(listOf("video"))[1].kidRequest?.request?.status)
    }
}
