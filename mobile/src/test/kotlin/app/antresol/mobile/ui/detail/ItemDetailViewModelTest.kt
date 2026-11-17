package app.antresol.mobile.ui.detail

import app.antresol.core.api.AntresolClient
import app.antresol.core.playback.PendingWrites
import app.antresol.ui.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The detail page's toggles tapped three times while the writes overlap: on, off, on. The server
 * refuses both «on» and accepts «off»: the page ends on «off», which is NOT what it showed after
 * the last tap. So the tests cannot pass without the undo having run, and their waits fail when
 * they give up.
 */
class ItemDetailViewModelTest {
    private val server = MockWebServer()

    /** The page's coroutines run on one thread of their own, one after another, as on a device. */
    private val main = Executors.newSingleThreadExecutor()

    /** What the server holds. */
    @Volatile private var watched = false
    @Volatile private var inWatchlist = false

    /** The page cannot be read again (the re-read after a refused write fails). */
    @Volatile private var offline = false

    /** The three writes have reached the server: none is answered before, so they overlap on the page. */
    private val received = CountDownLatch(3)
    private val accepted = CountDownLatch(1)
    private val refused = AtomicInteger()
    private val reread = AtomicInteger()

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                return when {
                    request.method == "GET" && path == "/api/items/100" ->
                        if (offline) MockResponse().setResponseCode(500).also { reread.incrementAndGet() }
                        else MockResponse().setBody("""{"id":100,"kind":"movie","title":"Movie","inWatchlist":$inWatchlist}""")
                    request.method == "GET" && path == "/api/progress/100" -> MockResponse().setBody("""{"finished":$watched}""")
                    path == "/api/items/100/watched" && "true" in body -> refusedAfterTheAccepted()
                    path == "/api/items/100/watched" -> acceptedOnceAllCame { watched = false }
                    request.method == "POST" && path == "/api/watchlist/100" -> refusedAfterTheAccepted()
                    request.method == "DELETE" && path == "/api/watchlist/100" -> acceptedOnceAllCame { inWatchlist = false }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun acceptedOnceAllCame(store: () -> Unit): MockResponse {
        received.countDown()
        assertTrue("the three writes did not all reach the server", received.await(WAIT_S, TimeUnit.SECONDS))
        store()
        accepted.countDown()
        return MockResponse().setResponseCode(204)
    }

    /** «On», the first and the last tap: answered after «off» was accepted, and refused. */
    private fun refusedAfterTheAccepted(): MockResponse {
        received.countDown()
        assertTrue("the accepted write was not answered", accepted.await(WAIT_S, TimeUnit.SECONDS))
        refused.incrementAndGet()
        return MockResponse().setResponseCode(500)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        main.shutdownNow()
    }

    private fun page(): ItemDetailViewModel {
        val vm = ItemDetailViewModel(AntresolClient(server.url("/").toString()), 100, PendingWrites())
        await("the page loads") { vm.state is LoadState.Ready }
        offline = true
        return vm
    }

    private val ItemDetailViewModel.shown: VideoDetail get() = (state as LoadState.Ready).value

    /** Waits for [done]; a wait that gives up fails the test. */
    private fun await(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!done()) {
            if (System.nanoTime() >= until) fail("Timed out waiting: $what")
            Thread.sleep(5)
        }
    }

    /** Whatever the page's thread had to do by now is done. */
    private fun idle() = main.submit {}.get(WAIT_S, TimeUnit.SECONDS)

    /** All three writes answered, the refusals told, the page asked for again (in vain) and that answer taken. */
    private fun awaitSettled(vm: ItemDetailViewModel, undone: () -> Boolean) {
        await("both refusals answered") { refused.get() == 2 }
        await("the refusal is told") { vm.notice != null }
        await("the page is read again") { reread.get() > 0 }
        await("the refused change is undone") { undone() }
        // The failed re-read reaches the page a moment after the server answered it.
        Thread.sleep(100)
        idle()
    }

    @Test
    fun watchedTappedThreeTimesEndsOnWhatTheServerAccepted() {
        val vm = page()
        vm.toggleWatched()
        assertEquals(true, vm.shown.watched)
        vm.toggleWatched()
        assertEquals(false, vm.shown.watched)
        vm.toggleWatched()
        // What the page shows while the writes are on their way: on. The server will have it off.
        assertEquals(true, vm.shown.watched)
        awaitSettled(vm) { !vm.shown.watched }
        assertEquals(false, watched)
        assertEquals(false, vm.shown.watched)
    }

    @Test
    fun theWatchlistTappedThreeTimesEndsOnWhatTheServerAccepted() {
        val vm = page()
        vm.toggleWatchlist()
        assertEquals(true, vm.shown.item.inWatchlist)
        vm.toggleWatchlist()
        assertEquals(false, vm.shown.item.inWatchlist)
        vm.toggleWatchlist()
        assertEquals(true, vm.shown.item.inWatchlist)
        awaitSettled(vm) { !vm.shown.item.inWatchlist }
        assertEquals(false, inWatchlist)
        assertEquals(false, vm.shown.item.inWatchlist)
    }

    private companion object {
        const val WAIT_S = 10L
    }
}
