package app.antresol.ui.requests

import app.antresol.core.api.ApiException
import app.antresol.core.model.KidAsk
import app.antresol.core.model.KidRequest
import app.antresol.core.model.KidRequestState
import app.antresol.core.model.RequestStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TitleRequestTest {
    private val ask = KidAsk(kind = "movie", tmdbId = 603, title = "Film")
    private val allowed = KidRequestState("tmdb:603", allowed = true)

    private fun TestScope.holder(api: FakeRequestsApi, onAsked: (KidRequest) -> Unit = {}) = TitleRequest(api, this, onAsked)

    @Test
    fun aKidsPageOffersAskingUntilARequestExists() = runTest(StandardTestDispatcher()) {
        val request = holder(FakeRequestsApi())
        assertFalse("not a kid's page yet", request.canAsk)
        request.load(allowed)
        assertTrue(request.canAsk)
        assertNull(request.why)
    }

    @Test
    fun askingMakesTheRequestAndTellsTheSearchPage() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { askGate = CompletableDeferred() }
        val told = ArrayList<KidRequest>()
        val request = holder(api, told::add)
        request.load(allowed)
        request.ask(ask)
        runCurrent()
        assertTrue(request.busy)
        request.ask(ask) // a second press while it is on its way
        api.askGate!!.complete(Unit)
        runCurrent()
        assertFalse(request.busy)
        assertEquals(listOf("ask movie 603"), api.calls)
        assertEquals(RequestStatus.PENDING, request.request?.status)
        assertFalse(request.canAsk)
        assertEquals(1, told.size)
        // Asked once: pressing again changes nothing.
        request.ask(ask)
        runCurrent()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aTitleAboveTheAgeLimitCannotBeAskedForAndSaysWhyInTheServersWords() = runTest(StandardTestDispatcher()) {
        val request = holder(FakeRequestsApi())
        request.load(KidRequestState("tmdb:603", allowed = false, blocked = "This is 16+"))
        assertFalse(request.canAsk)
        assertEquals("This is 16+", request.why)
    }

    @Test
    fun theServerRefusingTheRatingWhenAskedBlocksTheTitleWithItsReason() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { askFailure = ApiException(403, "This is 16+", code = "rating") }
        val request = holder(api)
        request.load(allowed)
        request.ask(ask)
        runCurrent()
        assertEquals("This is 16+", request.why)
        assertFalse(request.canAsk)
        assertFalse(request.failed)
        // The page read again: the server's word is the state.
        request.load(allowed)
        assertTrue(request.canAsk)
    }

    @Test
    fun anyOtherFailureIsAFailedAskThatCanBeTriedAgain() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi().apply { askFailure = IOException("down") }
        val request = holder(api)
        request.load(allowed)
        request.ask(ask)
        runCurrent()
        assertTrue(request.failed)
        assertTrue(request.canAsk)
        api.askFailure = null
        request.ask(ask)
        runCurrent()
        assertFalse(request.failed)
        assertEquals(RequestStatus.PENDING, request.request?.status)
    }

    @Test
    fun aPageWithNoKidStateOffersNothing() = runTest(StandardTestDispatcher()) {
        val api = FakeRequestsApi()
        val request = holder(api)
        request.ask(ask)
        runCurrent()
        assertTrue(api.calls.isEmpty())
    }
}
