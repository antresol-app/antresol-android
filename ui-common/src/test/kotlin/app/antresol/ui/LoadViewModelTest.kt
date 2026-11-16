package app.antresol.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LoadViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** What the server holds: the page re-reads it. */
    private var server = setOf("a")
    private var fetches = 0
    private var failures = 0

    private inner class Page : LoadViewModel<Set<String>>(fetch = { fetches++; server }) {
        /** Adds [tag] at once; the server accepts or refuses it when [answer] completes. */
        fun add(tag: String, answer: CompletableDeferred<Boolean>) = optimistic({ it + tag }, onFailure = { failures++ }) {
            if (!answer.await()) throw IOException("refused")
            server = server + tag
        }

        val shown: Set<String>? get() = (state as? LoadState.Ready)?.value
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun anAcceptedWriteStaysWithoutReadingThePageAgain() = runTest(dispatcher) {
        val page = Page()
        runCurrent()
        val answer = CompletableDeferred<Boolean>()
        page.add("b", answer)
        assertEquals(setOf("a", "b"), page.shown)
        answer.complete(true)
        runCurrent()
        assertEquals(setOf("a", "b"), page.shown)
        assertEquals(1, fetches)
        assertEquals(0, failures)
    }

    @Test
    fun aRefusedWriteIsUndone() = runTest(dispatcher) {
        val page = Page()
        runCurrent()
        val answer = CompletableDeferred<Boolean>()
        page.add("b", answer)
        answer.complete(false)
        runCurrent()
        assertEquals(setOf("a"), page.shown)
        assertEquals(1, failures)
        assertEquals(1, fetches)
    }

    @Test
    fun undoingARefusedWriteKeepsALaterOneTheServerAccepted() = runTest(dispatcher) {
        val page = Page()
        runCurrent()
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        page.add("b", first)
        page.add("c", second)
        assertEquals(setOf("a", "b", "c"), page.shown)
        first.complete(false)
        runCurrent()
        // Nothing is undone while another write is in flight.
        assertEquals(setOf("a", "b", "c"), page.shown)
        second.complete(true)
        runCurrent()
        assertEquals(setOf("a", "c"), page.shown)
        assertEquals(1, failures)
        assertEquals(2, fetches)
    }

    @Test
    fun whenEveryOverlappingWriteIsRefusedThePageIsAsBefore() = runTest(dispatcher) {
        val page = Page()
        runCurrent()
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        page.add("b", first)
        page.add("c", second)
        second.complete(false)
        first.complete(false)
        runCurrent()
        assertEquals(setOf("a"), page.shown)
        assertEquals(2, failures)
    }
}
