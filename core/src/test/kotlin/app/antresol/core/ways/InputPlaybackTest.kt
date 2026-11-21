package app.antresol.core.ways

import app.antresol.core.api.ApiException
import app.antresol.core.model.InputPlay
import app.antresol.core.model.PlayResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InputPlaybackTest {
    private val ready = InputPlay.Ready(PlayResponse(mode = "direct", url = "/x"))

    @Test
    fun asksAgainWhilePendingAndReturnsTheAnswer() = runTest {
        var asked = 0
        val waits = ArrayList<Long>()
        val play = InputPlayback.open(wait = { waits += it }) { if (++asked < 4) InputPlay.Pending else ready }
        assertEquals("/x", play.url)
        assertEquals(4, asked)
        assertEquals(listOf(1_500L, 1_500L, 1_500L), waits)
    }

    @Test
    fun givesUpAfterTheBudget() = runTest {
        var asked = 0
        var waited = 0L
        try {
            InputPlayback.open(budgetMs = 6_000, wait = { waited += it }) {
                asked++
                InputPlay.Pending
            }
            fail("expected a timeout")
        } catch (e: InputNotReadyException) {
            assertEquals(6_000L, waited)
            assertEquals(5, asked)
        }
    }

    @Test
    fun aFailureIsThrownAsItIs() = runTest {
        try {
            InputPlayback.open(wait = {}) { throw ApiException(502, "Unreadable") }
            fail("expected the server's error")
        } catch (e: ApiException) {
            assertEquals("Unreadable", e.message)
        }
    }

    @Test
    fun theTicketKeepsTheLastFewAndForgetsTheRest() {
        val display = PlaybackDisplay("T")
        val tokens = (1..10).map { WayTicket.put(WayPlayback.Input("in-$it", display)) }
        assertEquals(null, WayTicket.get(tokens.first()))
        assertEquals("in-10", (WayTicket.get(tokens.last()) as WayPlayback.Input).id)
        // Reading does not use it up: a rotation reads it again.
        assertEquals("in-10", (WayTicket.get(tokens.last()) as WayPlayback.Input).id)
    }
}
