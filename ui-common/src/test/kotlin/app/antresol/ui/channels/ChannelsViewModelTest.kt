package app.antresol.ui.channels

import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelList
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
        Channel(id = 2, number = 10, name = "Comedy", category = "Genres", now = ChannelProgram(title = "B"), logoUrl = "/api/channels/2/logo.png?v=1"),
        Channel(id = 3, number = 11, name = "Family", category = "Genres"),
    )

    private val favorites = mutableListOf<Pair<Long, Boolean>>()
    private var favoriteFails = false
    private val epgFroms = mutableListOf<String>()

    private val api = object : ChannelsApi {
        override suspend fun channels(): ChannelList {
            calls++
            if (failing) throw IOException("offline")
            return ChannelList(channels, lastChannel = 2)
        }

        override suspend fun setFavorite(channelId: Long, favorite: Boolean) {
            favorites += channelId to favorite
            if (favoriteFails) throw IOException("offline")
        }

        override suspend fun epg(fromIso: String, hours: Int): ChannelEpg {
            epgFroms += fromIso
            return ChannelEpg(channels = channels.map { ChannelEpgRow(it.id, it.number, it.name, programs = listOf(ChannelProgram(id = "${it.id}-$fromIso"))) })
        }

        override suspend fun opened() {
            openedCalls++
        }
    }

    private var openedCalls = 0

    private fun viewModel() = ChannelsViewModel(api, clock = { dispatcher.scheduler.currentTime })

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingTheTvPageTellsTheServerAtMostEveryTenMinutesAndReloads() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        val loads = calls
        vm.opened()
        runCurrent()
        assertEquals(1, openedCalls)
        // The server may have refreshed «при открытии» sources: the list is read again.
        assertEquals(loads + 1, calls)
        vm.opened()
        runCurrent()
        assertEquals(1, openedCalls)
        advanceTimeBy(10 * 60_000L + 1)
        vm.opened()
        runCurrent()
        assertEquals(2, openedCalls)
    }

    @Test
    fun oneListFilteredByThemeOrFavourites() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf("Movies", "Genres"), vm.themes)
        assertEquals(listOf(1L, 2L, 3L), vm.shown.map { it.id })
        assertEquals(2L, vm.lastChannel?.id)
        vm.select(ChannelFilter.Theme("Genres"))
        assertEquals(listOf(2L, 3L), vm.shown.map { it.id })
        vm.select(ChannelFilter.Favorites)
        assertEquals(emptyList<Long>(), vm.shown.map { it.id })
        vm.toggleFavorite(vm.channels!!.first { it.id == 3L })
        assertEquals(listOf(3L), vm.shown.map { it.id })
        runCurrent()
        assertEquals(listOf(3L to true), favorites)
        // Refused: undone.
        favoriteFails = true
        vm.toggleFavorite(vm.channels!!.first { it.id == 1L })
        runCurrent()
        assertEquals(listOf(3L), vm.shown.map { it.id })
        // A theme that disappeared falls back to «All».
        vm.select(ChannelFilter.Theme("Genres"))
        channels = channels.filter { it.category == "Movies" }
        vm.pullToRefresh()
        runCurrent()
        assertEquals(ChannelFilter.All, vm.filter)
    }

    @Test
    fun theGuidePagesForward() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.showView(ChannelsView.Guide)
        runCurrent()
        assertEquals(3, vm.shownGuide.size)
        val to = vm.guideToMs
        vm.loadMoreGuide()
        runCurrent()
        assertEquals(2, epgFroms.size)
        assertEquals(to + 6 * 3_600_000L, vm.guideToMs)
        assertEquals(2, vm.shownGuide.first().programs.size)
    }

    @Test
    fun theGuideShowsTheChannelListsLogos() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.showView(ChannelsView.Guide)
        runCurrent()
        assertEquals(listOf(null, "/api/channels/2/logo.png?v=1", null), vm.shownGuide.map { it.logoUrl })
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
        assertEquals(3, vm.channels?.size)
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
        assertNull(vm.channels)
        failing = false
        vm.reload()
        runCurrent()
        assertNull(vm.error)
        assertEquals(3, vm.channels?.size)
    }

    @Test
    fun theLastChannelComesFromTheServerAndFollowsTuneIn() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals("Comedy", vm.lastChannel?.name)
        vm.tunedIn(3)
        assertEquals("Family", vm.lastChannel?.name)
        vm.tunedIn(42)
        assertNull(vm.lastChannel)
    }
}
