package app.antresol.ui.channels

import app.antresol.core.channels.Channels
import app.antresol.core.channels.InMemoryLastChannelStorage
import app.antresol.core.channels.LastChannels
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var calls = 0
    private var failing = false
    private var channels = listOf(
        Channel(id = 1, number = 1, name = "All movies", category = "Movies", now = ChannelProgram(title = "A")),
        Channel(id = 2, number = 10, name = "Comedy", category = "Genres", now = ChannelProgram(title = "B")),
        Channel(id = 3, number = 11, name = "Family", category = "Genres"),
    )

    private fun viewModel() = ChannelsViewModel(
        fetch = {
            calls++
            if (failing) throw IOException("offline")
            channels
        },
        clock = { dispatcher.scheduler.currentTime },
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun groupsSectionsAndPicksOne() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf("Movies", "Genres"), vm.sections?.map { it.category })
        assertEquals(2, vm.shown.size)
        vm.select("Genres")
        assertEquals("Genres", vm.selectedSection)
        assertEquals(listOf(2L, 3L), vm.shown.single().channels.map { it.id })
        // A section that disappeared falls back to «All».
        channels = channels.filter { it.category == "Movies" }
        vm.pullToRefresh()
        runCurrent()
        assertNull(vm.selectedSection)
        assertEquals(listOf("Movies"), vm.shown.map { it.category })
    }

    @Test
    fun onAirListsChannelsWithAProgrammeByNumber() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf(1L, 2L), vm.onAir.map { it.id })
    }

    @Test
    fun refreshesEveryThirtySecondsWhileShownAndKeepsDataOnFailure() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.startRefreshing()
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(Channels.REFRESH_MS + 1)
        assertEquals(2, calls)
        failing = true
        advanceTimeBy(Channels.REFRESH_MS)
        assertEquals(3, calls)
        assertNull(vm.error)
        assertEquals(2, vm.sections?.size)
        vm.stopRefreshing()
        advanceTimeBy(Channels.REFRESH_MS * 3)
        assertEquals(3, calls)
    }

    @Test
    fun aFirstFailureShowsTheErrorUntilRetried() = runTest(dispatcher) {
        failing = true
        val vm = viewModel()
        runCurrent()
        assertNotNull(vm.error)
        assertNull(vm.sections)
        failing = false
        vm.reload()
        runCurrent()
        assertNull(vm.error)
        assertEquals(2, vm.sections?.size)
    }

    @Test
    fun continueFollowsTheLastWatchedChannel() = runTest(dispatcher) {
        val last = LastChannels(InMemoryLastChannelStorage()).of("server", 1)
        val vm = ChannelsViewModel(fetch = { channels }, clock = { dispatcher.scheduler.currentTime }, lastChannel = last)
        runCurrent()
        assertNull(vm.continueChannel)
        // The player remembers a channel while the page is behind it.
        last.remember(2)
        runCurrent()
        assertEquals("Comedy", vm.continueChannel?.name)
        // A channel that is gone (or hidden from this profile) is not offered.
        last.remember(42)
        runCurrent()
        assertNull(vm.continueChannel)
    }
}
