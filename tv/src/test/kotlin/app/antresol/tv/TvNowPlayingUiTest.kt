package app.antresol.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onLast
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.LyricLine
import app.antresol.core.model.Lyrics
import app.antresol.core.model.Profile
import app.antresol.core.model.Track
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.tv.playback.SleepTimer
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.nowplaying.LyricsList
import app.antresol.tv.ui.nowplaying.NowPlayingScreen
import app.antresol.tv.ui.nowplaying.NowPlayingViewModel
import app.antresol.tv.ui.shell.MainShell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The music Now Playing screen on the TV (1920x1080 at xhdpi): the transport and the pills under the seek bar sit on the
 * axis of the column the bar spans, the times stand at the bar's ends, and the lyrics are shown without an empty line,
 * with an instrumental stretch once, the current line a third of the way down. D-pad focus walks the rows in order.
 * Screenshots go to `tv/build/screenshots/now-playing-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvNowPlayingUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

    private val title = "Morning Light (Extended Orchestral Version with the Whole Choir and a Very Long Subtitle)"

    private fun track(title: String = "Morning Light", hasLyrics: Boolean = true) = Track(
        id = 901, title = title, artist = "Northern Static", album = "Golden Hours", albumId = 900, trackNo = 1,
        durationMs = 60_000, hasLyrics = hasLyrics,
    )

    /** Signs in against the fake server; every track answers with [lyricsJson] (null: none). */
    private fun signIn(lyricsJson: String?) {
        fake.lyricsJson = lyricsJson
        fake.start()
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
    }

    /** Plays [track] and shows the screen alone, with the pieces [MainShell] hands it. */
    private fun start(track: Track = track(), lyricsJson: String? = SYNCED) {
        signIn(lyricsJson)
        val client = checkNotNull(app.session.client)
        app.audio.playTracks(listOf(track))
        compose.setContent {
            AntresolTvTheme {
                CompositionLocalProvider(LocalMediaUrls provides client.urls) {
                    NowPlayingScreen(remember { NowPlayingViewModel(client, app.audio) })
                }
            }
        }
        waitFor(hasContentDescription("Pause") or hasContentDescription("Play"))
    }

    /** Plays a song and opens the screen the way the system's now-playing card does: inside the real shell. */
    private fun startInShell() {
        signIn(SYNCED)
        val session = app.session
        app.audio.playTracks(listOf(track()))
        compose.setContent {
            val state by session.state.collectAsState()
            (state as? AuthState.SignedIn)?.let { AntresolTvTheme { MainShell(session, it) } }
        }
        waitFor(hasText("The Test Show"))
        app.nowPlayingRequested.value = true
        waitFor(hasText("NOW PLAYING"))
        waitFor(hasContentDescription("Pause") or hasContentDescription("Play"))
        settle()
    }

    @After
    fun tearDown() {
        app.audio.stop()
        fake.shutdown()
    }

    private fun bounds(matcher: SemanticsMatcher): DpRect = compose.onNode(matcher).getBoundsInRoot()

    private fun tagged(tag: String) = bounds(hasTestTag(tag))

    private fun center(rect: DpRect) = (rect.left + rect.right) / 2

    private val DpRect.w: Dp get() = right - left

    private val DpRect.h: Dp get() = bottom - top

    private fun assertNear(message: String, expected: Dp, actual: Dp, tolerance: Dp = 1.5.dp) {
        assertTrue("$message: expected $expected, was $actual", abs(expected.value - actual.value) <= tolerance.value)
    }

    private fun playButton() = hasContentDescription("Pause") or hasContentDescription("Play")

    /** The focused element that is [matcher] or holds it (a button's label is a node under the focused surface). */
    private fun focusedOn(matcher: SemanticsMatcher) = isFocused() and (matcher or hasAnyDescendant(matcher))

    /** The pill that says [text] (the clickable that holds it). */
    private fun pill(text: String) = hasClickAction() and hasText(text)

    @Test
    fun theTransportAndThePillsSitOnTheAxisOfTheSeekBarAndItsTimesAtItsEnds() {
        start()
        shot("now-playing-music")
        val bar = tagged("now-playing-seek")
        val axis = center(bar)
        // The bar spans the column: it is as wide as the screen leaves it (48 dp padding, a 320 dp panel, 36 dp gap, 40 dp padding).
        assertNear("seek bar width", (960 - 48 - 40 - 320 - 36).dp, bar.w)

        // The transport: Play/Pause on the axis, the others symmetrically around it, inside the bar.
        val play = bounds(playButton())
        val shuffle = bounds(hasContentDescription("Shuffle"))
        val repeat = bounds(hasContentDescription("Repeat"))
        assertNear("play/pause centre", axis, center(play))
        assertNear("transport centre", axis, (shuffle.left + repeat.right) / 2)
        assertTrue("the transport is inside the bar", shuffle.left >= bar.left && repeat.right <= bar.right)

        // The pills: one group, centred on the same axis, with one gap between each, inside the bar.
        val pills = listOf("Up Next", "Favorite", "Sleep Timer").map { bounds(pill(it)) }
        assertNear("pills centre", axis, (pills.first().left + pills.last().right) / 2)
        val gaps = pills.zipWithNext { a, b -> b.left - a.right }
        assertNear("the gaps between the pills", gaps[0], gaps[1], 0.5.dp)
        assertTrue("the pills are inside the bar", pills.first().left >= bar.left && pills.last().right <= bar.right)

        // The times: the elapsed one starts where the bar starts, the remaining one ends where it ends.
        val elapsed = bounds(hasText("0:00"))
        val remaining = bounds(textMatching(Regex("-\\d+:\\d\\d")))
        assertNear("elapsed time starts at the bar", bar.left, elapsed.left)
        assertNear("remaining time ends at the bar", bar.right, remaining.right)
    }

    @Test
    fun aLongTitleWrapsInsideItsBlockAndTheByLineRestsOnTheCoversBottom() {
        start(track(title = title))
        shot("now-playing-music-long-title")
        val cover = tagged("now-playing-cover")
        val bar = tagged("now-playing-seek")
        // The cover's placeholder draws the title too: the title block is the later of the two.
        val shownTitle = compose.onAllNodes(hasText(title)).onLast().getBoundsInRoot()
        val byline = bounds(hasText("Northern Static · Golden Hours"))
        assertTrue("the title starts to the right of the cover", shownTitle.left > cover.right)
        assertTrue("the title stays inside the column", shownTitle.right <= bar.right)
        assertNear("the byline's bottom is the cover's", cover.bottom, byline.bottom, tolerance = 12.dp)
        assertTrue("the title block does not outgrow the cover", shownTitle.top >= cover.top)
        // The controls do not move with the title.
        assertNear("play/pause centre", center(bar), center(bounds(playButton())))
    }

    @Test
    fun aRunningSleepTimerShowsItsStateWithoutCuttingAPill() {
        start()
        app.audio.startSleepTimer(SleepTimer.EndOfItem)
        waitFor(hasText("End of Song"))
        shot("now-playing-music-sleep")
        val bar = tagged("now-playing-seek")
        val pills = listOf("Up Next", "Favorite", "End of Song").map { bounds(pill(it)) }
        assertTrue("the pills are inside the bar: $pills vs $bar", pills.first().left >= bar.left && pills.last().right <= bar.right)
        assertNear("pills centre", center(bar), (pills.first().left + pills.last().right) / 2)
        // No label is wider than its pill: nothing is clipped.
        listOf("Up Next", "Favorite", "End of Song").forEach { label ->
            assertTrue("«$label» is cut", !textOverflows(label))
        }
        // A screen reader says what the pill is, not only its state.
        compose.onNode(hasContentDescription("Sleep Timer · End of Song")).assertExists()
    }

    @Test
    fun theDpadWalksTheRowsInOrder() {
        start()
        // The screen opens on Play/Pause.
        waitFor(focusedOn(playButton()))
        shot("now-playing-focus-play")
        // Up: the seek bar; Down again: back to Play/Pause.
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor(isFocused() and hasTestTag("now-playing-seek"))
        shot("now-playing-focus-seek")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(focusedOn(playButton()))
        // Along the transport: Previous, Shuffle, and back past Play/Pause to Next and Repeat.
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor(focusedOn(hasContentDescription("Previous")))
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor(focusedOn(hasContentDescription("Shuffle")))
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 4)
        waitFor(focusedOn(hasContentDescription("Repeat")))
        // Down from Play/Pause: the middle pill; along the pills and back up.
        key(KeyEvent.KEYCODE_DPAD_LEFT, times = 2)
        waitFor(focusedOn(playButton()))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(focusedOn(hasText("Favorite")))
        shot("now-playing-focus-pill")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor(focusedOn(hasText("Up Next")))
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2)
        waitFor(focusedOn(hasText("Sleep Timer")))
        // Up from the last pill: a transport button on the right half, not the far-left one.
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor(focusedOn(hasContentDescription("Repeat") or hasContentDescription("Next")))
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor(isFocused() and hasTestTag("now-playing-seek"))
    }

    @Test
    fun insideTheShellTheDpadLeavesTheSeekBarToo() {
        startInShell()
        shot("now-playing-shell")
        // Opens on Play/Pause, as the screen alone does.
        waitFor(focusedOn(playButton()))
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor(isFocused() and hasTestTag("now-playing-seek"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(focusedOn(playButton()))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(focusedOn(hasText("Favorite")))
    }

    @Test
    fun theLyricsHaveNoEmptyLineAndAnInstrumentalStretchIsOneNote() {
        start()
        waitFor(hasText("I walked down to the river"))
        shot("now-playing-lyrics")
        // The intro's empty line, the break's two lines and the outro's empty line: one note, between two sung lines.
        compose.onAllNodesWithText("♪").assertCountEquals(1)
        val lines = rowTexts("lyrics")
        assertTrue("no empty line: $lines", lines.none { it.isBlank() })
        assertEquals("I walked down to the river", lines.first())
        val note = lines.indexOf("♪")
        assertTrue("the note is between two sung lines: $lines", note > 0 && note < lines.lastIndex)
    }

    @Test
    fun plainLyricsKeepTheirStanzasWithoutAnEmptyLine() {
        start(lyricsJson = PLAIN)
        waitFor(hasText("Slow tide, slow tide"))
        shot("now-playing-lyrics-plain")
        val lines = rowTexts("lyrics")
        assertTrue("no empty line: $lines", lines.none { it.isBlank() })
        assertTrue("no note: $lines", "♪" !in lines)
        // Plain lyrics have no current line: they start under the heading.
        assertTrue("starts under the heading", bounds(hasText("Slow tide, slow tide")).top - bounds(hasText("Lyrics")).bottom < 40.dp)
    }

    @Test
    fun aSongWithoutLyricsSaysSo() {
        start(track(hasLyrics = false), lyricsJson = null)
        waitFor(hasText("No lyrics for this song."))
        shot("now-playing-no-lyrics")
    }

    @Test
    fun theCurrentLineRestsAThirdOfTheWayDownAndTheListKeepsItThereToTheEnd() {
        var position by mutableLongStateOf(0L)
        val lyrics = Lyrics(synced = true, lines = (1..30).map { LyricLine(it * 3.0, "Line $it") })
        compose.setContent {
            AntresolTvTheme {
                Box(Modifier.width(320.dp).fillMaxHeight().testTag("panel")) {
                    LyricsList(lyrics, positionMs = { position })
                }
            }
        }
        val panel = tagged("panel")
        fun lineTop(n: Int): Dp = bounds(hasText("Line $n")).top - panel.top

        // Before the first line nothing is current: the first line waits where the current line will be.
        settle()
        assertNear("line 1 waits at the third", panel.h / 3, lineTop(1), 14.dp)
        // In the middle of the song: the current line is a third of the way down.
        for (n in listOf(10, 20, 29)) {
            position = n * 3_000L + 100
            settle()
            assertNear("line $n at the third", panel.h / 3, lineTop(n), 14.dp)
        }
        // The last line gets there too, the lines sung before it leave through the top.
        position = 30 * 3_000L + 100
        settle()
        assertNear("last line at the third", panel.h / 3, lineTop(30), 14.dp)
        shot("now-playing-lyrics-last-line")
        // The song starts again: the list is back where it was before the first line.
        position = 0L
        settle()
        assertNear("line 1 waits at the third again", panel.h / 3, lineTop(1), 14.dp)
    }

    // region helpers

    private fun textMatching(regex: Regex) = SemanticsMatcher("text matches $regex") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { regex.matches(it.text) } == true
    }

    /** Whether the text [label] is wider than the room it was given. */
    private fun textOverflows(label: String): Boolean {
        val node = compose.onAllNodes(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().single()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.any { it.didOverflowWidth }
    }

    /** The texts of the rows drawn in the list tagged [tag], top to bottom. */
    private fun rowTexts(tag: String): List<String> =
        compose.onNode(hasTestTag(tag)).onChildren().fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        }

    private fun settle() {
        repeat(30) {
            Thread.sleep(30)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }

    private fun press(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        press(code)
        compose.waitForIdle()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
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
            shot("now-playing-failure")
            throw IllegalStateException("${e.message}", e)
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

    private companion object {
        /** Lyrics as the public lyrics services give them: an empty intro line, a break of two lines, an empty outro line. */
        val SYNCED = """{"lyrics":"","synced":true,"source":"lrc","lines":[
            {"t":0.0,"text":""},{"t":3.0,"text":"I walked down to the river"},{"t":6.0,"text":"Where the lanterns drift"},
            {"t":9.0,"text":"Nobody knows my name"},{"t":12.0,"text":"♪"},{"t":15.0,"text":""},
            {"t":18.0,"text":"I know the river is patient now"},{"t":22.0,"text":""}]}"""

        val PLAIN = """{"lyrics":"\n\nSlow tide, slow tide\nCarry me home\n\n\nThe lamps are lit along the quay\n\n","synced":false,"source":"lrc","lines":[]}"""

    }
}
