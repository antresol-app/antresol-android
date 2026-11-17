package app.antresol.mobile.ui.detail

import app.antresol.core.api.AntresolClient
import app.antresol.core.playback.PendingWrites
import app.antresol.ui.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The detail page's toggles against a server that refuses the first of two overlapping writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailViewModelTest {
    private val server = MockWebServer()

    /** What the server holds. */
    @Volatile private var watched = false
    @Volatile private var inWatchlist = false

    /** The page cannot be read again (the re-read after a refused write fails). */
    @Volatile private var offline = false
    private val accepted = CountDownLatch(1)
    private val settled = AtomicInteger()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                return when {
                    request.method == "GET" && path == "/api/items/100" ->
                        if (offline) MockResponse().setResponseCode(500).also { settled.incrementAndGet() }
                        else MockResponse().setBody("""{"id":100,"kind":"movie","title":"Movie","inWatchlist":$inWatchlist}""")
                    request.method == "GET" && path == "/api/progress/100" -> MockResponse().setBody("""{"finished":$watched}""")
                    // The first tap (on) is answered after the second (off), and refused.
                    path == "/api/items/100/watched" && "true" in body -> refusedLater()
                    path == "/api/items/100/watched" -> MockResponse().setResponseCode(204).also { watched = false; accepted.countDown() }
                    request.method == "POST" && path == "/api/watchlist/100" -> refusedLater()
                    request.method == "DELETE" && path == "/api/watchlist/100" -> MockResponse().setResponseCode(204).also { inWatchlist = false; accepted.countDown() }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun refusedLater(): MockResponse {
        accepted.await(5, TimeUnit.SECONDS)
        // Well after the accepted answer has reached the page.
        Thread.sleep(300)
        return MockResponse().setResponseCode(500)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun page(): ItemDetailViewModel {
        val vm = ItemDetailViewModel(AntresolClient(server.url("/").toString()), 100, PendingWrites())
        await { vm.state is LoadState.Ready }
        offline = true
        return vm
    }

    private val ItemDetailViewModel.shown: VideoDetail get() = (state as LoadState.Ready).value

    private fun await(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < until) Thread.sleep(10)
    }

    /** Both writes answered, the page asked for again (in vain), and what that leaves has been shown. */
    private fun awaitSettled(vm: ItemDetailViewModel) {
        await { settled.get() > 0 && vm.notice != null }
        Thread.sleep(200)
    }

    @Test
    fun watchedTappedTwiceEndsOnWhatTheServerAccepted() {
        val vm = page()
        vm.toggleWatched()
        assertEquals(true, vm.shown.watched)
        vm.toggleWatched()
        assertEquals(false, vm.shown.watched)
        // On was refused, off accepted; the page cannot be read again: off, as the server has it.
        awaitSettled(vm)
        assertEquals(false, watched)
        assertEquals(false, vm.shown.watched)
    }

    @Test
    fun theWatchlistTappedTwiceEndsOnWhatTheServerAccepted() {
        val vm = page()
        vm.toggleWatchlist()
        assertEquals(true, vm.shown.item.inWatchlist)
        vm.toggleWatchlist()
        assertEquals(false, vm.shown.item.inWatchlist)
        awaitSettled(vm)
        assertEquals(false, vm.shown.item.inWatchlist)
    }
}
