package app.antresol.core.ways

import app.antresol.core.api.ApiException
import app.antresol.core.model.RunResult
import app.antresol.core.model.StepRow
import app.antresol.core.model.WayPlay
import app.antresol.core.model.WayScope
import app.antresol.core.model.WayState
import app.antresol.core.model.WayStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaysControllerTest {
    private val movie = WayTarget("movie", 603, inLibrary = false, title = "The Matrix", poster = "/p", backdrop = "/b")
    private val watch = way("watch", episodes = true)
    private val keep = way("keep", role = "keep", scope = WayScope.OUTSIDE)

    private fun TestScope.controller(api: FakeWaysApi, vararg ways: app.antresol.core.model.AddonWay, maxHeight: Int? = null): WaysController {
        val source = FakeSource(AddonWays(ways.toList(), emptyList()))
        return WaysController(api, source, backgroundScope, maxHeight = { maxHeight }, pollMs = 5_000).also { it.attach(movie) }
    }

    @Test
    fun attachOffersTheWaysByRole() = runTest {
        val c = controller(FakeWaysApi(), watch, keep)
        runCurrent()
        assertEquals(listOf("watch"), c.offer.value.watch.map { it.id })
        assertEquals(listOf("keep"), c.offer.value.keep.map { it.id })
    }

    @Test
    fun aQuickRunThatAnswersAnInputPlaysItThroughTheInputPlayer() = runTest {
        val api = FakeWaysApi().apply { runs = { RunResult(input = "in-1") } }
        val c = controller(api, watch, maxHeight = 1080)
        runCurrent()
        c.start(WayCall(watch, movie))
        runCurrent()
        val outcome = c.outcomes.first()
        val play = (outcome as WayOutcome.Play).playback as WayPlayback.Input
        assertEquals("in-1", play.id)
        assertEquals("The Matrix", play.display.title)
        val request = api.runRequests.single()
        assertEquals(true, request.quick)
        assertNull(request.ref)
        assertEquals(1080, request.maxHeight)
        assertEquals("watch", request.way)
        assertEquals(603L, request.tmdbId)
        assertNull(c.busy.value)
    }

    @Test
    fun aQuickRunThatAnswersAStreamKeepsTheRunForARefresh() = runTest {
        val stream = WayPlay(url = "/api/addons/alpha/x/media/s/index.m3u8", kind = "hls", session = "s")
        val api = FakeWaysApi().apply { runs = { RunResult(play = stream) } }
        val c = controller(api, watch)
        runCurrent()
        c.start(WayCall(watch, WayTarget("series", 1399, true, title = "Show"), season = 1, episode = 2))
        runCurrent()
        val direct = (c.outcomes.first() as WayOutcome.Play).playback as WayPlayback.Direct
        assertEquals("alpha", direct.addonId)
        assertEquals("s", direct.play.session)
        assertEquals(1, direct.request.season)
        assertEquals(2, direct.request.episode)
        assertEquals(1, direct.display.season)
    }

    @Test
    fun aDoneAnswerShowsTheTextAndAsksTheStateAgain() = runTest {
        var active = false
        val api = FakeWaysApi().apply {
            steps = { WayStep("Quality", listOf(StepRow("best", "Best", isFinal = true))) }
            runs = { active = true; RunResult(done = "Started") }
            states = { if (active) mapOf("keep" to WayState(active = true, fraction = 0.1)) else emptyMap() }
        }
        val c = controller(api, keep)
        runCurrent()
        assertTrue(c.states.value.getValue("alpha/keep").let { !it.active })
        c.start(WayCall(keep, movie))
        runCurrent()
        c.choose(c.picker.value!!.step!!.rows.single())
        runCurrent()
        assertEquals("Started", (c.outcomes.first() as WayOutcome.Done).text)
        assertTrue(c.states.value.getValue("alpha/keep").active)
        assertNull(c.picker.value)
    }

    @Test
    fun aKeepWayAlwaysOpensTheChoiceAndNeverRunsQuick() = runTest {
        val api = FakeWaysApi().apply {
            steps = { WayStep("Quality", listOf(StepRow("any", "Any", isFinal = false), StepRow("best", "Best", isFinal = true))) }
            runs = { RunResult(done = "Started") }
        }
        val c = controller(api, keep)
        runCurrent()
        // A tap is the same as a hold: the first step, no run.
        c.start(WayCall(keep, movie))
        runCurrent()
        assertEquals("Quality", c.picker.value!!.step!!.title)
        assertNull(c.busy.value)
        assertTrue(api.runRequests.isEmpty())
        assertNull(api.stepRequests.single().ref)
        // The run comes from a final row, with its ref and without `quick`.
        c.choose(c.picker.value!!.step!!.rows[1])
        runCurrent()
        val run = api.runRequests.single()
        assertEquals("best", run.ref)
        assertNull(run.quick)
        assertEquals("keep", run.way)
    }

    @Test
    fun aPickAnswerOpensTheChoiceFromItsFirstStep() = runTest {
        val api = FakeWaysApi().apply {
            runs = { RunResult(pick = true) }
            steps = { WayStep(title = "Source", rows = listOf(StepRow("r1", "One"))) }
        }
        val c = controller(api, watch)
        runCurrent()
        c.start(WayCall(watch, movie))
        runCurrent()
        val picker = c.picker.value!!
        assertEquals("Source", picker.step!!.title)
        assertFalse(picker.loading)
        assertNull(api.stepRequests.single().ref)
        assertNull(c.busy.value)
    }

    @Test
    fun theChainOfStepsGoesForwardAndBackAndRunsOnAFinalRow() = runTest {
        val api = FakeWaysApi().apply {
            steps = { request ->
                when (request.ref) {
                    null -> WayStep("Source", listOf(StepRow("src", "A source")))
                    "src" -> WayStep("Voice", listOf(StepRow("voice", "A voice")))
                    else -> WayStep("Quality", listOf(StepRow("q1080", "1080p", isFinal = true)))
                }
            }
            runs = { RunResult(input = "in-9") }
        }
        val c = controller(api, watch)
        runCurrent()
        c.start(WayCall(watch, movie), choose = true)
        runCurrent()
        assertEquals(listOf("Source"), c.picker.value!!.steps.map { it.title })
        assertFalse(c.picker.value!!.canGoBack)

        c.choose(c.picker.value!!.step!!.rows.single())
        runCurrent()
        c.choose(c.picker.value!!.step!!.rows.single())
        runCurrent()
        assertEquals(listOf("Source", "Voice", "Quality"), c.picker.value!!.steps.map { it.title })
        assertEquals(listOf(null, "src", "voice"), api.stepRequests.map { it.ref })
        assertTrue(c.picker.value!!.canGoBack)

        // Back shows the step before without asking again.
        c.back()
        assertEquals("Voice", c.picker.value!!.step!!.title)
        assertEquals(3, api.stepRequests.size)
        c.choose(c.picker.value!!.step!!.rows.single())
        runCurrent()
        c.choose(c.picker.value!!.step!!.rows.single())
        runCurrent()

        // A final row runs the way with its ref, and the sheet closes on the answer.
        assertNull(c.picker.value)
        val run = api.runRequests.single()
        assertEquals("q1080", run.ref)
        assertNull(run.quick)
        assertEquals("in-9", ((c.outcomes.first() as WayOutcome.Play).playback as WayPlayback.Input).id)
    }

    @Test
    fun aFailedStepKeepsTheSheetOnTheLastGoodStepWithTheError() = runTest {
        val api = FakeWaysApi().apply { steps = { WayStep("Source", listOf(StepRow("src", "A source"))) } }
        val c = controller(api, watch)
        runCurrent()
        c.start(WayCall(watch, movie), choose = true)
        runCurrent()
        api.stepFailure = ApiException(502, "Source unavailable")
        c.choose(c.picker.value!!.step!!.rows.single())
        runCurrent()
        val picker = c.picker.value!!
        assertEquals("Source unavailable", picker.error!!.message)
        assertFalse(picker.loading)
        assertEquals(1, picker.steps.size)
        // The same row can be tried again.
        api.stepFailure = null
        c.choose(picker.step!!.rows.single())
        runCurrent()
        assertEquals(2, c.picker.value!!.steps.size)
    }

    @Test
    fun aFailedRunKeepsTheSheetOpenToPickAnother() = runTest {
        val api = FakeWaysApi().apply {
            steps = { WayStep("Quality", listOf(StepRow("a", "A", isFinal = true), StepRow("b", "B", isFinal = true))) }
            runFailure = ApiException(502, "Could not start")
        }
        val c = controller(api, watch)
        runCurrent()
        c.start(WayCall(watch, movie), choose = true)
        runCurrent()
        c.choose(c.picker.value!!.step!!.rows[0])
        runCurrent()
        assertEquals("Could not start", c.picker.value!!.error!!.message)
        assertFalse(c.picker.value!!.running)
        api.runFailure = null
        api.runs = { RunResult(done = "ok") }
        c.choose(c.picker.value!!.step!!.rows[1])
        runCurrent()
        assertNull(c.picker.value)
        assertEquals("b", api.runRequests.last().ref)
    }

    @Test
    fun backOnTheFirstStepOrWhileItLoadsClosesTheSheet() = runTest {
        val api = FakeWaysApi().apply { steps = { WayStep("Source", listOf(StepRow("src", "A source"))) } }
        val c = controller(api, watch)
        runCurrent()
        c.start(WayCall(watch, movie), choose = true)
        assertNotNull(c.picker.value)
        c.back() // the first step is still being asked for
        assertNull(c.picker.value)
        runCurrent()
        assertNull("a late answer does not reopen it", c.picker.value)

        c.start(WayCall(watch, movie), choose = true)
        runCurrent()
        c.back()
        assertNull(c.picker.value)
    }

    @Test
    fun aWayTheServerNoLongerKnowsIsAskedForAgain() = runTest {
        val api = FakeWaysApi().apply { runFailure = ApiException(404, "Gone") }
        val source = FakeSource(AddonWays(listOf(watch), emptyList()))
        val c = WaysController(api, source, backgroundScope).also { it.attach(movie) }
        runCurrent()
        c.start(WayCall(watch, movie))
        runCurrent()
        assertEquals("Gone", ((c.outcomes.first()) as WayOutcome.Failed).error.message)
        assertEquals(1, source.invalidated)
    }

    @Test
    fun theStateIsAskedEveryFiveSecondsWhileAWayIsActiveAndStopsWhenNothingIs() = runTest {
        var rounds = 0
        val api = FakeWaysApi().apply {
            states = {
                rounds++
                mapOf("keep" to WayState(active = rounds < 4, fraction = rounds / 4.0, text = "Part $rounds", cancel = true))
            }
        }
        val c = controller(api, keep)
        runCurrent()
        assertEquals(1, rounds)
        assertEquals("Part 1", c.states.value.getValue("alpha/keep").text)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, rounds)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, rounds)
        assertFalse(c.states.value.getValue("alpha/keep").active)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no more asking once nothing is active", 4, rounds)
    }

    @Test
    fun noStateIsAskedWithoutAKeepWay() = runTest {
        val api = FakeWaysApi()
        controller(api, watch)
        runCurrent()
        assertTrue(api.calls.none { it.startsWith("state") })
    }

    @Test
    fun cancelStopsTheWayAndRefreshesItsState() = runTest {
        var active = true
        val api = FakeWaysApi().apply {
            states = { mapOf("keep" to WayState(active = active, cancel = true)) }
            cancelResult = RunResult(done = "Cancelled")
        }
        val c = controller(api, keep)
        runCurrent()
        assertTrue(c.states.value.getValue("alpha/keep").cancel)
        active = false
        c.cancel(keep)
        runCurrent()
        assertEquals("Cancelled", (c.outcomes.first() as WayOutcome.Done).text)
        assertFalse(c.states.value.getValue("alpha/keep").active)
        assertTrue(api.calls.contains("cancel alpha keep"))
    }

    @Test
    fun aSecondTapWhileTheFirstRunIsGoingIsIgnored() = runTest {
        val api = FakeWaysApi().apply { runs = { RunResult(done = "ok") } }
        val c = controller(api, watch)
        runCurrent()
        val call = WayCall(watch, movie)
        c.start(call)
        c.start(call)
        runCurrent()
        assertEquals(1, api.runRequests.size)
    }

    @Test
    fun anEpisodeOffersItsOwnWays() = runTest {
        val series = WayTarget("series", 1399, inLibrary = true)
        val api = FakeWaysApi()
        val c = WaysController(api, FakeSource(AddonWays(listOf(watch, way("out", scope = WayScope.OUTSIDE, episodes = true), way("whole")), emptyList())), backgroundScope)
        c.attach(series)
        runCurrent()
        assertEquals(listOf("watch"), c.forEpisode(inLibrary = true).map { it.id })
        assertEquals(listOf("watch", "out"), c.forEpisode(inLibrary = false).map { it.id })
    }
}
