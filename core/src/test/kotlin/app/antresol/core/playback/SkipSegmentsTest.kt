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
        assertEquals(0.5, SkipSegments.countdown(5_000), 1e-9)
        assertEquals(1.0, SkipSegments.countdown(100_000), 1e-9)
        assertEquals(0.0, SkipSegments.countdown(-3_000), 1e-9)
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
        // a tick every second, playing: ten seconds of it fill the pill
        val start = t.at(2500.0, nowMs = 0).pill as SkipPill.Next
        assertTrue(start.countdown)
        assertEquals(0f, start.fill)
        for (s in 1..4) t.at(2500.0 + s, nowMs = s * 1_000L)
        assertEquals(0.5f, (t.at(2505.0, nowMs = 5_000).pill as SkipPill.Next).fill)
        for (s in 6..9) assertNull(t.at(2500.0 + s, nowMs = s * 1_000L).auto)
        assertEquals(SkipTracker.Auto.Next, t.at(2510.0, nowMs = 10_000).auto)
        // only once
        assertNull(t.at(2511.0, nowMs = 11_000).auto)
        assertFalse(t.takeNext())
    }

    @Test
    fun seekingForwardInTheCreditsDoesNotSkipTheCountdown() {
        val t = SkipTracker(episode)
        t.at(2500.0, nowMs = 0)
        t.at(2501.0, nowMs = 1_000)
        // +30 s, then the position moves on: the fill follows the playing time, not the file's
        val after = t.at(2580.0, nowMs = 1_500)
        assertEquals(0.15f, (after.pill as SkipPill.Next).fill)
        assertNull(after.auto)
        assertEquals(0.2f, (t.at(2581.0, nowMs = 2_000).pill as SkipPill.Next).fill)
        // a jump back into the credits keeps counting too
        assertEquals(0.25f, (t.at(2510.0, nowMs = 2_500).pill as SkipPill.Next).fill)
    }

    @Test
    fun countdownCountsOnlyPlayingTime() {
        val t = SkipTracker(episode)
        t.at(2500.0, nowMs = 0)
        // paused for half a minute: nothing fills, and resuming does not add the pause
        assertEquals(0f, (t.at(2500.0, nowMs = 5_000, playing = false).pill as SkipPill.Next).fill)
        assertEquals(0f, (t.at(2500.0, nowMs = 30_000, playing = false).pill as SkipPill.Next).fill)
        assertEquals(0f, (t.at(2500.25, nowMs = 30_250).pill as SkipPill.Next).fill)
        assertEquals(0.05f, (t.at(2500.75, nowMs = 30_750).pill as SkipPill.Next).fill)
        // a stall (no tick for 30 s while "playing") counts for the gap limit at most
        assertEquals(0.2f, (t.at(2501.0, nowMs = 60_750).pill as SkipPill.Next).fill)
    }

    @Test
    fun leavingAndEnteringTheCreditsFillsAfresh() {
        val t = SkipTracker(episode)
        t.at(2500.0, nowMs = 0)
        t.at(2504.0, nowMs = 1_000)
        t.at(2505.0, nowMs = 2_000)
        assertNull(t.at(2400.0, nowMs = 3_000).pill)
        assertEquals(0f, (t.at(2500.0, nowMs = 4_000).pill as SkipPill.Next).fill)
        assertEquals(0.1f, (t.at(2501.0, nowMs = 5_000).pill as SkipPill.Next).fill)
    }

    @Test
    fun noCountdownWithAPostCreditsSceneOrAutoplayOff() {
        val scene = SkipTracker(episode).at(2500.0, duration = 2700.0).pill as SkipPill.Next
        assertFalse(scene.countdown)
        val tracker = SkipTracker(episode)
        tracker.at(2500.0, nowMs = 0, autoplay = false)
        val later = tracker.at(2550.0, nowMs = 50_000, autoplay = false)
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
