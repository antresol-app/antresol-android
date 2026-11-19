package app.antresol.ui.channels

import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelCategory
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
    private var serverCategories: List<ChannelCategory>? = null
    private var failing = false
    private var favoritesFirst = false
    private var channels = listOf(
        Channel(id = 1, number = 1, name = "All movies", category = "Movies", now = ChannelProgram(title = "A")),
        Channel(id = 2, number = 10, name = "Comedy", category = "Genres", now = ChannelProgram(title = "B"), logoUrl = "/api/channels/2/logo.png?v=1"),
        Channel(id = 3, number = 11, name = "Family", category = "Genres"),
    )

    private val favorites = mutableListOf<Pair<Long, Boolean>>()
    private var favoriteFails = false
    private val epgFroms = mutableListOf<String>()
    private var epgFails = false

    private val api = object : ChannelsApi {
        override suspend fun channels(): ChannelList {
            calls++
            if (failing) throw IOException("offline")
            return ChannelList(channels, lastChannel = 2, categories = serverCategories, favoritesFirst = favoritesFirst)
        }

        override suspend fun setFavorite(channelId: Long, favorite: Boolean) {
            favorites += channelId to favorite
            if (favoriteFails) throw IOException("offline")
        }

        override suspend fun epg(fromIso: String, hours: Int): ChannelEpg {
            epgFroms += fromIso
            if (epgFails) throw IOException("offline")
            return ChannelEpg(channels = channels.map { ChannelEpgRow(it.id, it.number, it.name, programs = listOf(ChannelProgram(id = "${it.id}-$fromIso", start = fromIso))) })
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
    fun theServersCategoriesNameAndOrderTheFilters() = runTest(dispatcher) {
        serverCategories = listOf(
            ChannelCategory("ai:comedy", "Юмор", ChannelCategory.KIND_AI, 2),
            ChannelCategory("group:Genres", "Genres", ChannelCategory.KIND_GROUP, 2),
        )
        channels = channels.map { it.copy(categories = if (it.category == "Genres") listOf("ai:comedy", "group:Genres") else listOf("group:Movies")) }
        val vm = viewModel()
        runCurrent()
        assertEquals(
            listOf(
                ChannelFilter.Favorites to 0,
                ChannelFilter.All to 3,
                ChannelFilter.Category("ai:comedy") to 2,
                ChannelFilter.Category("group:Genres") to 2,
            ),
            vm.filterOptions.map { it.filter to it.count },
        )
        assertEquals("Юмор", vm.filterOptions[2].label)
        vm.select(ChannelFilter.Category("ai:comedy"))
        assertEquals(listOf(2L, 3L), vm.shown.map { it.id })
        // The server stops sending them (an older one answers the next refresh): the channels' own texts again.
        serverCategories = null
        channels = channels.map { it.copy(categories = null) }
        vm.reload()
        runCurrent()
        assertEquals(ChannelFilter.All, vm.filter)
        assertEquals(listOf("Movies", "Genres"), vm.filterOptions.drop(2).map { it.label })
    }

    @Test
    fun oneListFilteredByThemeOrFavourites() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(
            listOf(
                ChannelFilter.Favorites to 0,
                ChannelFilter.All to 3,
                ChannelFilter.Category("group:Movies") to 1,
                ChannelFilter.Category("group:Genres") to 2,
            ),
            vm.filterOptions.map { it.filter to it.count },
        )
        assertEquals(listOf(1L, 2L, 3L), vm.shown.map { it.id })
        assertEquals(2L, vm.lastChannel?.id)
        vm.select(ChannelFilter.Category("group:Genres"))
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
        vm.select(ChannelFilter.Category("group:Genres"))
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
    fun aGuidePageThatFailedIsNotAskedForAgainAtOnce() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.showView(ChannelsView.Guide)
        runCurrent()
        val to = vm.guideToMs
        epgFails = true
        // The guide asks on every scroll position near the end: one request, not one per frame.
        repeat(5) {
            vm.loadMoreGuide()
            runCurrent()
        }
        assertEquals(2, epgFroms.size)
        assertEquals(to, vm.guideToMs)
        // Scrolling on after a pause asks again; a page that loads is merged as ever.
        advanceTimeBy(3_000)
        runCurrent()
        epgFails = false
        vm.loadMoreGuide()
        runCurrent()
        assertEquals(3, epgFroms.size)
        assertEquals(to + 6 * 3_600_000L, vm.guideToMs)
    }

    @Test
    fun aGuidePageThatFailedIsAskedForAgainWithoutAnotherScroll() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startRefreshing()
        runCurrent()
        vm.showView(ChannelsView.Guide)
        runCurrent()
        val to = vm.guideToMs
        epgFails = true
        // The phone: the request fails at once, the fling goes on to the end of the scroll within the
        // pause (every call ignored), and at the end nothing calls any more.
        repeat(5) {
            vm.loadMoreGuide()
            runCurrent()
        }
        assertEquals(2, epgFroms.size)
        // 3 s later the page is asked for again by itself, then 6 s after that; the network is back by then.
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(3, epgFroms.size)
        assertEquals(to, vm.guideToMs)
        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(3, epgFroms.size)
        epgFails = false
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, epgFroms.size)
        assertEquals(epgFroms[1], epgFroms[3])
        assertEquals(to + 6 * 3_600_000L, vm.guideToMs)
        vm.stopRefreshing()
    }

    @Test
    fun aGuidePageThatFailedWaitsForThePageToComeBack() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startRefreshing()
        runCurrent()
        vm.showView(ChannelsView.Guide)
        runCurrent()
        val to = vm.guideToMs
        epgFails = true
        vm.loadMoreGuide()
        runCurrent()
        // The app is left during the pause: nothing is asked while nobody looks.
        vm.stopRefreshing()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, epgFroms.size)
        // Back: the page is asked for, though the scroll position did not change.
        epgFails = false
        vm.startRefreshing()
        runCurrent()
        assertEquals(3, epgFroms.size)
        assertEquals(to + 6 * 3_600_000L, vm.guideToMs)
        vm.stopRefreshing()
    }

    @Test
    fun theViewersOwnRequestIsNotIgnoredWhileAFailedGuidePageWaits() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startRefreshing()
        runCurrent()
        vm.showView(ChannelsView.Guide)
        runCurrent()
        val to = vm.guideToMs
        epgFails = true
        vm.loadMoreGuide()
        // 0, 3, 9, 21 s: four failures, the pause is 24 s now.
        advanceTimeBy(21_000)
        runCurrent()
        assertEquals(5, epgFroms.size)
        // Right at the guide's end 5 s into the pause: asked for at once, not 19 s later.
        advanceTimeBy(5_000)
        vm.loadMoreGuide(explicit = true)
        runCurrent()
        assertEquals(6, epgFroms.size)
        // The key held: one request a second, not one per repeat.
        repeat(10) {
            advanceTimeBy(100)
            vm.loadMoreGuide(explicit = true)
            runCurrent()
        }
        assertEquals(7, epgFroms.size)
        // The pauses start over: 3 s after the last answer the job asks by itself.
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(7, epgFroms.size)
        epgFails = false
        advanceTimeBy(1)
        runCurrent()
        assertEquals(8, epgFroms.size)
        assertEquals(to + 6 * 3_600_000L, vm.guideToMs)
        // A scroll position (not the viewer's key) during the pause is still ignored.
        epgFails = true
        vm.loadMoreGuide()
        runCurrent()
        repeat(5) {
            advanceTimeBy(500)
            vm.loadMoreGuide()
            runCurrent()
        }
        assertEquals(9, epgFroms.size)
        vm.stopRefreshing()
    }

    @Test
    fun aGuidePageThatKeepsFailingIsNotAskedForForEver() = runTest(dispatcher) {
        val vm = viewModel()
        // The phone's Home shelf watches the channels for as long as Home is shown.
        vm.startRefreshing()
        runCurrent()
        vm.showView(ChannelsView.Guide)
        runCurrent()
        val to = vm.guideToMs
        epgFails = true
        vm.loadMoreGuide()
        // Five requests (0, 3, 9, 21, 45 s), then none, however long the page stays.
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(6, epgFroms.size)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(6, epgFroms.size)
        // The viewer scrolls on, or the page comes back: asked for again.
        vm.loadMoreGuide()
        runCurrent()
        assertEquals(7, epgFroms.size)
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(11, epgFroms.size)
        epgFails = false
        vm.startRefreshing(watcher = "guide")
        runCurrent()
        assertEquals(12, epgFroms.size)
        assertEquals(to + 6 * 3_600_000L, vm.guideToMs)
        vm.stopRefreshing("guide")
        vm.stopRefreshing()
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

    @Test
    fun theServersOrderIsKeptAndAStarMovesTheChannelWhenFavouritesComeFirst() = runTest(dispatcher) {
        // The server's order (favourites first): not sorted again by number here.
        favoritesFirst = true
        channels = listOf(channels[2].copy(favorite = true), channels[0], channels[1])
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf(3L, 1L, 2L), vm.shown.map { it.id })
        vm.showView(ChannelsView.Guide)
        runCurrent()
        assertEquals(listOf(3L, 1L, 2L), vm.shownGuide.map { it.id })
        // A star given here: the channel joins the favourites at the top, by number among them.
        vm.toggleFavorite(channels[2])
        runCurrent()
        assertEquals(listOf(2L, 3L, 1L), vm.shown.map { it.id })
        assertEquals(listOf(2L, 3L, 1L), vm.shownGuide.map { it.id })
        // Taken away: back among the rest by number.
        vm.toggleFavorite(vm.shown.first { it.id == 3L })
        runCurrent()
        assertEquals(listOf(2L, 1L, 3L), vm.shown.map { it.id })
    }

    @Test
    fun withoutFavouritesFirstAStarMovesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.toggleFavorite(channels[2])
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), vm.shown.map { it.id })
        assertEquals(true, vm.shown.last().favorite)
    }
}
