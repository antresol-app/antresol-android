package app.antresol.core.playback

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AutoSkip
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.PlaySegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsTest {
    private val recap = PlaySegment("recap", 0.0, 40.0)
    private val intro = PlaySegment("intro", 60.0, 150.0)
    private val credits = PlaySegment("credits", 2500.0, 2590.0)
    private val episode = listOf(credits, intro, recap)
    private val off = AutoSkip()

    @Test
    fun playAnswerCarriesTheSegments() {
        val play = AntresolClient.json.decodeFromString(
            PlayResponse.serializer(),
            """{"mode":"direct","url":"/api/stream/1","segments":[{"kind":"intro","startSec":60,"endSec":150.5}]}""",
        )
        assertEquals(listOf(PlaySegment("intro", 60.0, 150.5)), play.segments)
        // an older server sends none
        assertTrue(AntresolClient.json.decodeFromString(PlayResponse.serializer(), """{"mode":"direct","url":"/x"}""").segments.isEmpty())
    }

    @Test
    fun usableDropsShortAndUnknownSegmentsAndSorts() {
        val segs = SkipSegments.usable(
            episode + PlaySegment("intro", 200.0, 202.9) + PlaySegment("preview", 300.0, 400.0) + PlaySegment("recap", 500.0, Double.NaN),
        )
        assertEquals(listOf(recap, intro, credits), segs)
        assertEquals(1, SkipSegments.usable(listOf(PlaySegment("intro", 10.0, 13.0))).size) // 3 s exactly is offered
    }

    @Test
    fun segmentAtCountsTheLastQuarterSecondAsOut() {
        val segs = SkipSegments.usable(episode)
        assertEquals(intro, SkipSegments.at(segs, 60.0))
        assertEquals(intro, SkipSegments.at(segs, 149.7))
        assertNull(SkipSegments.at(segs, 149.8)) // a seek to its end lands here
        assertNull(SkipSegments.at(segs, 59.9))
        assertNull(SkipSegments.at(segs, 45.0))
    }

    @Test
    fun postCreditsSceneAndCreditsToEnd() {
        assertTrue(SkipSegments.postCreditsScene(credits, 2620.0)) // 30 s after the credits
        assertFalse(SkipSegments.postCreditsScene(credits, 2605.0)) // 15 s: not a scene
        assertFalse(SkipSegments.postCreditsScene(credits, 0.0)) // unknown duration
        assertTrue(SkipSegments.creditsToEnd(credits, 2590.5))
        assertFalse(SkipSegments.creditsToEnd(credits, 2600.0))
        assertTrue(SkipSegments.creditsToEnd(credits, 0.0))
        assertEquals(0.5, SkipSegments.countdown(2500.0, 2505.0), 1e-9)
        assertEquals(1.0, SkipSegments.countdown(2500.0, 2600.0), 1e-9)
        assertEquals(0.0, SkipSegments.countdown(2500.0, 2490.0), 1e-9)
    }

    private fun SkipTracker.at(
        sec: Double,
        nowMs: Long = 0,
        playing: Boolean = true,
        duration: Double = 2600.0,
        hasNext: Boolean = true,
        autoplay: Boolean = true,
        auto: AutoSkip = off,
    ) = step(sec, nowMs, playing, duration, hasNext, autoplay, auto)

    @Test
    fun introPillShowsAloneForEightSecondsThenOnlyWithTheControls() {
        val t = SkipTracker(episode)
        assertNull(t.at(50.0).pill)
        val first = t.at(61.0, nowMs = 1_000).pill as SkipPill.Skip
        assertEquals("intro", first.kind)
        assertTrue(first.alone)
        assertEquals(SkipAction.Seek(150_000), first.action)
        assertTrue((t.at(68.0, nowMs = 8_999).pill as SkipPill.Skip).alone)
        assertFalse((t.at(69.0, nowMs = 9_000).pill as SkipPill.Skip).alone)
        // leaving and entering again starts the 8 s afresh
        t.at(40.0, nowMs = 10_000)
        assertTrue((t.at(61.0, nowMs = 20_000).pill as SkipPill.Skip).alone)
    }

    @Test
    fun autoSkipSeeksPastARecapOnceWithANote() {
        val t = SkipTracker(episode)
        val auto = AutoSkip(recap = true)
        // paused: nothing by itself
        assertNull(t.at(1.0, playing = false, auto = auto).auto)
        val step = t.at(1.5, auto = auto)
        assertEquals(SkipTracker.Auto.Skipped(40_000, SkipNote("recap", 0)), step.auto)
        assertNull(step.pill)
        // «Вернуть» seeks back into it: it is not skipped again, the pill is offered instead
        val back = t.at(0.5, auto = auto)
        assertNull(back.auto)
        assertEquals("recap", (back.pill as SkipPill.Skip).kind)
        // the intro is not auto-skipped with only the recap on
        assertNull(t.at(70.0, auto = auto).auto)
    }

    @Test
    fun creditsOfAnEpisodeOfferNextAndCountDown() {
        val t = SkipTracker(episode)
        val start = t.at(2500.0, nowMs = 0).pill as SkipPill.Next
        assertTrue(start.countdown)
        assertEquals(0f, start.fill)
        assertEquals(0.5f, (t.at(2505.0).pill as SkipPill.Next).fill)
        assertNull(t.at(2509.0).auto)
        assertEquals(SkipTracker.Auto.Next, t.at(2510.0).auto)
        // only once
        assertNull(t.at(2511.0).auto)
    }

    @Test
    fun noCountdownWithAPostCreditsSceneOrAutoplayOff() {
        val scene = SkipTracker(episode).at(2500.0, duration = 2700.0).pill as SkipPill.Next
        assertFalse(scene.countdown)
        val tracker = SkipTracker(episode)
        tracker.at(2500.0, autoplay = false)
        val later = tracker.at(2550.0, autoplay = false)
        assertEquals(SkipPill.Next(0f, countdown = false), later.pill)
        assertNull(later.auto)
    }

    @Test
    fun watchCreditsDismissesNext() {
        val t = SkipTracker(episode)
        t.at(2500.0)
        t.creditsDismissed = true
        val step = t.at(2515.0)
        assertNull(step.pill)
        assertNull(step.auto)
        assertNull(t.at(2501.0, auto = AutoSkip(credits = true)).auto)
    }

    @Test
    fun creditsOfAFilmOrLastEpisodeSkipToTheirEndOrTheEnd() {
        val toEnd = SkipTracker(episode).at(2500.0, duration = 2590.5, hasNext = false).pill as SkipPill.Skip
        assertEquals("credits", toEnd.kind)
        assertEquals(SkipAction.End, toEnd.action)
        val before = SkipTracker(episode).at(2500.0, duration = 2700.0, hasNext = false).pill as SkipPill.Skip
        assertEquals(SkipAction.Seek(2_590_000), before.action)
    }

    @Test
    fun autoCreditsGoesToNextOrEndUnlessASceneFollows() {
        val auto = AutoSkip(credits = true)
        assertEquals(SkipTracker.Auto.Next, SkipTracker(episode).at(2500.0, auto = auto).auto)
        assertEquals(SkipTracker.Auto.End, SkipTracker(episode).at(2500.0, hasNext = false, auto = auto).auto)
        assertNull(SkipTracker(episode).at(2500.0, duration = 2700.0, auto = auto).auto)
        // a press of «Следующая серия» after the automatic one does nothing
        val t = SkipTracker(episode)
        t.at(2500.0, auto = auto)
        assertFalse(t.takeNext())
    }
}
