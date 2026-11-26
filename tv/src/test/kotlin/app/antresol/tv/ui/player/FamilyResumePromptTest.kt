package app.antresol.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.fixtures.FakeServer
import app.antresol.fixtures.FakeServer.FakeFamilyMember
import app.antresol.tv.AntresolTvApp
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.settings.TvPreferences
import app.antresol.ui.playback.PlayerPhase
import app.antresol.ui.playback.ResumeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The resume prompt with the family: when a film or episode starts and somebody else of the household is somewhere this profile
 * is not, the prompt gets a row for each of them (at most three); a row copies their place into this profile's own and plays
 * from it. Against [FakeServer] (which answers no film: what the player asked for is read from [FakeServer.plays]).
 * Screenshots go to `tv/build/screenshots/resume-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FamilyResumePromptTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }
    private val store = ViewModelStore()
    private var keys = 0

    private val movie = FakeServer.MOVIE_ID
    private val episodes = FakeServer.EPISODE_IDS
    private val min = 60_000L

    private fun daria(positionMs: Long, agoMinutes: Long = 120, itemId: Long = movie, userId: Long = 2) =
        FakeFamilyMember(userId, "Daria", itemId, positionMs = positionMs, durationMs = 104 * min, agoMinutes = agoMinutes)

    private var signedIn = false

    private fun signIn() {
        if (signedIn) return
        signedIn = true
        fake.start()
        runBlocking {
            app.session.restore()
            if (app.session.state.value !is app.antresol.core.session.AuthState.NoServer) app.session.changeServer()
            app.session.connect(fake.baseUrl)
            app.session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
    }

    private fun player(itemId: Long, mode: ResumeMode): PlayerViewModel {
        signIn()
        val client = app.session.requireClient()
        return ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(app, client, app.appScope, itemId, mode, TvPreferences(), app.pendingWrites) as T
            },
        )["player-${keys++}", PlayerViewModel::class.java]
    }

    private fun show(player: PlayerViewModel) {
        compose.setContent { AntresolTvTheme { PlayerScreen(player, onExit = {}) } }
    }

    @After
    fun tearDown() {
        store.clear()
        fake.shutdown()
    }

    private fun asked(player: PlayerViewModel): PlayerPhase.AskResume {
        waitUntil { player.phase is PlayerPhase.AskResume || player.phase is PlayerPhase.Failed }
        return player.phase as? PlayerPhase.AskResume ?: error("no prompt: ${player.phase}; requests ${fake.requests.takeLast(10)}")
    }

    /** The start went on without asking: the player asked for the stream (the fake has none, so it then failed). */
    private fun playedWithoutAsking(player: PlayerViewModel) {
        waitUntil { fake.plays.isNotEmpty() }
        assertFalse(player.phase is PlayerPhase.AskResume)
    }

    @Test
    fun aFirstTimeWithOneMemberOffersPlayAndTheirPlaceAndNoStartingOver() {
        fake.family[movie] = listOf(daria(72 * min + 40_000))
        val player = player(movie, ResumeMode.Resume)
        val prompt = asked(player)
        assertEquals(0L, prompt.positionMs)
        assertFalse(prompt.hasOwnPlace)
        assertEquals(listOf("Daria"), prompt.family.map { it.name })
        show(player)
        waitFor(hasText("Where to start?"))
        waitFor(hasText("Play"))
        waitFor(hasText("Daria · 1:12:40"))
        // No «from the beginning» for somebody who has not started; focus on the first row.
        assertTrue(compose.onAllNodes(hasTestTag("resume-start"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        waitFor(isFocused() and hasTestTag("resume-own"))
        settle()
        shot("resume-first-time")
    }

    @Test
    fun withAPlaceOfOneAndTwoMembersTheRowsStandInTheOrderOfRecency() {
        fake.progress[movie] = (34 * min + 10_000) to 104 * min
        fake.family[movie] = listOf(
            daria(72 * min + 40_000, agoMinutes = 300, userId = 2),
            FakeFamilyMember(3, "Sam", movie, positionMs = 20 * min, durationMs = 104 * min, agoMinutes = 30),
            // Within two minutes of this profile's own place: not offered.
            FakeFamilyMember(4, "Robin", movie, positionMs = 35 * min, durationMs = 104 * min, agoMinutes = 10),
            // Finished: not offered.
            FakeFamilyMember(5, "Kim", movie, positionMs = 90 * min, durationMs = 104 * min, finished = true, agoMinutes = 5),
        )
        val player = player(movie, ResumeMode.Resume)
        val prompt = asked(player)
        assertEquals(34 * min + 10_000, prompt.positionMs)
        assertEquals(listOf("Sam", "Daria"), prompt.family.map { it.name })
        show(player)
        waitFor(hasText("Resume · 34:10"))
        waitFor(hasText("Sam · 20:00"))
        waitFor(hasText("Daria · 1:12:40"))
        waitFor(hasText("From Beginning"))
        waitFor(isFocused() and hasTestTag("resume-own"))
        // Top to bottom: the own place, the family, starting over.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(isFocused() and hasTestTag("resume-family-3"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(isFocused() and hasTestTag("resume-family-2"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(isFocused() and hasTestTag("resume-start"))
        key(KeyEvent.KEYCODE_DPAD_UP, 3)
        waitFor(isFocused() and hasTestTag("resume-own"))
        settle()
        shot("resume-two-members")
    }

    @Test
    fun noMoreThanThreeMembersAreListed() {
        fake.family[movie] = (2L..6L).map { daria(30 * min + it * 5 * min, agoMinutes = it * 10, userId = it).copy(name = "M$it") }
        val player = player(movie, ResumeMode.Resume)
        assertEquals(listOf("M2", "M3", "M4"), asked(player).family.map { it.name })
    }

    @Test
    fun aMemberRowCopiesTheirPlaceThenPlaysFromIt() {
        fake.progress[movie] = (34 * min + 10_000) to 104 * min
        fake.family[movie] = listOf(daria(72 * min + 40_000))
        val player = player(movie, ResumeMode.Resume)
        asked(player)
        show(player)
        waitFor(hasText("Daria · 1:12:40"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { fake.plays.isNotEmpty() }
        assertEquals(listOf("$movie 2"), fake.adopted.toList())
        // Adopt first, then the stream, from their place.
        assertTrue(fake.requests.indexOf("POST /api/progress/$movie/adopt") < fake.requests.indexOfFirst { it.startsWith("POST /api/play/") })
        assertTrue(fake.plays.single(), fake.plays.single().contains("\"startSec\":4360"))
        assertEquals(1000L + movie, fake.plays.single().substringBefore(' ').toLong())
    }

    @Test
    fun theOwnRowPlaysFromTheOwnPlaceAndStartingOverFromZero() {
        fake.progress[movie] = (34 * min + 10_000) to 104 * min
        fake.family[movie] = listOf(daria(72 * min + 40_000))
        val own = player(movie, ResumeMode.Resume)
        asked(own)
        own.chooseResume(true)
        waitUntil { fake.plays.size == 1 }
        assertTrue(fake.plays.single(), fake.plays.single().contains("\"startSec\":2050"))
        assertTrue(fake.adopted.isEmpty())
        val again = player(movie, ResumeMode.Resume)
        asked(again)
        again.chooseResume(false)
        waitUntil { fake.plays.size == 2 }
        assertFalse(fake.plays.last(), fake.plays.last().contains("\"startSec\":2050") || fake.plays.last().contains("\"startSec\":4360"))
        assertTrue(fake.adopted.isEmpty())
    }

    @Test
    fun aRefusedCopyStillSeeksToTheirPlaceWithoutAnError() {
        fake.adoptRefused = true
        fake.family[movie] = listOf(daria(72 * min + 40_000))
        val player = player(movie, ResumeMode.Resume)
        asked(player)
        player.chooseFamily(player.phase.let { (it as PlayerPhase.AskResume).family.single() })
        waitUntil { fake.plays.isNotEmpty() }
        assertTrue(fake.plays.single(), fake.plays.single().contains("\"startSec\":4360"))
        // The only error is the fake's missing stream, never the copy: the request for it was made.
        assertEquals(listOf("POST /api/progress/$movie/adopt"), fake.requests.filter { it.contains("/adopt") })
    }

    @Test
    fun anotherEpisodeOfTheSeriesIsOfferedWithItsNumberAndPlayed() {
        // The series play button: this profile's next episode is the second (the fake's `next`); Daria is in the fourth.
        fake.family[FakeServer.SERIES_ID] = listOf(
            FakeFamilyMember(2, "Daria", episodes[3], positionMs = 12 * min + 40_000, durationMs = 45 * min, season = 1, episode = 4, agoMinutes = 90),
        )
        val player = player(FakeServer.SERIES_ID, ResumeMode.Resume)
        val prompt = asked(player)
        assertEquals(episodes[3], prompt.family.single().itemId)
        assertEquals(episodes[1], player.item?.id)
        show(player)
        waitFor(hasText("Daria · S1 E4 · 12:40"))
        settle()
        shot("resume-series-episode")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { fake.plays.isNotEmpty() }
        assertEquals(listOf("${episodes[3]} 2"), fake.adopted.toList())
        // The fourth episode's file, from 12:40.
        assertEquals(1000L + episodes[3], fake.plays.single().substringBefore(' ').toLong())
        assertTrue(fake.plays.single(), fake.plays.single().contains("\"startSec\":760"))
        assertEquals(episodes[3], player.item?.id)
    }

    // region Which episode the family may be in: only when the app chose the episode

    private fun seriesFamily() {
        fake.family[FakeServer.SERIES_ID] = listOf(
            FakeFamilyMember(5, "Daria", episodes[3], positionMs = 12 * min + 40_000, durationMs = 45 * min, season = 1, episode = 4, agoMinutes = 15),
            FakeFamilyMember(2, "Sam", episodes[1], positionMs = 20 * min, durationMs = 45 * min, season = 1, episode = 2, agoMinutes = 120),
        )
        // What the server answers for the episode itself: whoever has progress in that very episode.
        fake.family[episodes[1]] = listOf(
            FakeFamilyMember(2, "Sam", episodes[1], positionMs = 20 * min, durationMs = 45 * min, season = 1, episode = 2, agoMinutes = 120),
        )
        fake.family[episodes[3]] = listOf(
            FakeFamilyMember(5, "Daria", episodes[3], positionMs = 12 * min + 40_000, durationMs = 45 * min, season = 1, episode = 4, agoMinutes = 15),
        )
    }

    @Test
    fun anEpisodePickedFromTheListHasOnlyThoseWhoAreInThatEpisode() {
        seriesFamily()
        val player = player(episodes[1], ResumeMode.Ask)
        val prompt = asked(player)
        assertEquals(listOf("Sam"), prompt.family.map { it.name })
        assertEquals(episodes[1], prompt.family.single().itemId)
        // The family of the episode was asked for, not that of the series.
        assertTrue(fake.requests.contains("GET /api/items/${episodes[1]}/family"))
        assertFalse(fake.requests.contains("GET /api/items/${FakeServer.SERIES_ID}/family"))
    }

    @Test
    fun anEpisodePickedFromTheListWithOthersOnlyElsewhereStartsAtOnce() {
        seriesFamily()
        fake.family.remove(episodes[1])
        playedWithoutAsking(player(episodes[1], ResumeMode.Ask))
    }

    @Test
    fun aResumeOfAnEpisodeAndTheSeriesPlayOfferAnotherEpisodeToo() {
        // «Continue» on the series page: the app chose episode two, Daria is in episode four.
        seriesFamily()
        val resumed = asked(player(episodes[1], ResumeMode.Resume))
        assertEquals(listOf("Daria", "Sam"), resumed.family.map { it.name })
        assertEquals(listOf(episodes[3], episodes[1]), resumed.family.map { it.itemId })
        // The series' Play: the app resolves the episode.
        assertEquals(listOf("Daria", "Sam"), asked(player(FakeServer.SERIES_ID, ResumeMode.Resume)).family.map { it.name })
        // An episode the series' Play chose is still the app's choice with «Ask» (the series resolved to it).
        assertEquals(listOf("Daria", "Sam"), asked(player(FakeServer.SERIES_ID, ResumeMode.Ask)).family.map { it.name })
    }

    // endregion

    // region No prompt when the place was chosen already

    @Test
    fun aPlaceChosenByHandIsNeverAskedAndPlaysFromTheOwnPlace() {
        // A family jump on the title page: their place is this profile's own now, and a third profile is elsewhere.
        fake.progress[movie] = (34 * min + 10_000) to 104 * min
        fake.family[movie] = listOf(daria(72 * min + 40_000))
        val player = player(movie, ResumeMode.Chosen)
        playedWithoutAsking(player)
        assertTrue(fake.plays.single(), fake.plays.single().contains("\"startSec\":2050"))
        // The same for an episode with somebody in another one.
        seriesFamily()
        fake.progress[episodes[1]] = (8 * min) to 45 * min
        val episode = player(episodes[1], ResumeMode.Chosen)
        waitUntil { fake.plays.size == 2 }
        assertFalse(episode.phase is PlayerPhase.AskResume)
        assertTrue(fake.plays.last(), fake.plays.last().startsWith("${1000 + episodes[1]} ") && fake.plays.last().contains("\"startSec\":480"))
    }

    @Test
    fun aPlaceTheStartWasGivenIsNeverAsked() {
        // A family row of the prompt itself starts another episode at their place: no second prompt for the third profile.
        seriesFamily()
        fake.family[FakeServer.SERIES_ID] = fake.family.getValue(FakeServer.SERIES_ID) +
            FakeFamilyMember(6, "Kim", episodes[2], positionMs = 9 * min, durationMs = 45 * min, season = 1, episode = 3, agoMinutes = 200)
        val player = player(episodes[1], ResumeMode.Resume)
        val prompt = asked(player)
        player.chooseFamily(prompt.family.first { it.name == "Daria" })
        waitUntil { fake.plays.isNotEmpty() }
        assertEquals(1000L + episodes[3], fake.plays.single().substringBefore(' ').toLong())
        assertFalse(player.phase is PlayerPhase.AskResume)
    }

    @Test
    fun theNextEpisodeAfterAnEpisodeNeverAsks() {
        fake.playable = true
        val player = player(episodes[0], ResumeMode.Resume)
        waitUntil { player.phase == PlayerPhase.Playing && player.nextEpisode != null }
        assertEquals(episodes[1], player.nextEpisode?.id)
        // Somebody is elsewhere in the series by the time the next episode starts: autoplay and the Next button just play it.
        seriesFamily()
        player.playNext()
        waitUntil { fake.plays.any { it.startsWith("${1000 + episodes[1]} ") } }
        assertFalse(player.phase is PlayerPhase.AskResume)
    }

    // endregion

    @Test
    fun nobodyElseOrNobodyFarAwayLeavesTheStartAsItWas() {
        // Nobody else has progress: a resume plays at once.
        fake.progress[movie] = (34 * min + 10_000) to 104 * min
        playedWithoutAsking(player(movie, ResumeMode.Resume))
    }

    @Test
    fun aPlaceWithinTwoMinutesOfTheOwnIsNotAnotherPlace() {
        fake.progress[movie] = (34 * min + 10_000) to 104 * min
        fake.family[movie] = listOf(daria(35 * min), daria(0, userId = 3))
        playedWithoutAsking(player(movie, ResumeMode.Resume))
    }

    @Test
    fun theAskModeKeepsItsOwnPromptAloneWhenNobodyQualifies() {
        fake.progress[movie] = (34 * min + 10_000) to 104 * min
        val player = player(movie, ResumeMode.Ask)
        val prompt = asked(player)
        assertTrue(prompt.family.isEmpty())
        show(player)
        waitFor(hasText("Resume from 34:10"))
        waitFor(hasText("Play from Beginning"))
    }

    @Test
    fun playingFromTheBeginningIsNeverAsked() {
        fake.family[movie] = listOf(daria(72 * min))
        playedWithoutAsking(player(movie, ResumeMode.FromStart))
    }

    @Test
    fun aKidIsOfferedNobody() {
        fake.kid = true
        fake.family[movie] = listOf(daria(72 * min))
        playedWithoutAsking(player(movie, ResumeMode.Resume))
    }

    // region Driving the UI

    private fun press(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        press(code)
        compose.waitForIdle()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out; requests ${fake.requests.takeLast(14)}" }
            Thread.sleep(20)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) {
                Thread.sleep(10)
                ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(16)
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            shot("resume-failure")
            throw IllegalStateException("${e.message}; requests ${fake.requests.takeLast(14)}", e)
        }
    }

    private fun shot(name: String) {
        if (compose.mainClock.autoAdvance) compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // endregion
}
