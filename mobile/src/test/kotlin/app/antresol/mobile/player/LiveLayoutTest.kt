package app.antresol.mobile.player

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.channels.LiveStream
import app.antresol.core.channels.LiveTvState
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import app.antresol.mobile.ui.channels.ChannelRow
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * The live player's bars and a channel list row on small screens: nothing may run past the screen
 * (owner's round-4 feedback from a real phone). Renders with Robolectric's native graphics; with
 * ANTRESOL_SHOTS_DIR set, the frames are written there as PNGs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private val program = ChannelProgram(
        id = "3-1", itemId = 1, kind = "movie",
        title = "The Wolf of Wall Street: an extraordinarily long title for a small phone",
        start = iso(now - 40 * 60_000), end = iso(now + 80 * 60_000),
    )
    private val next = program.copy(id = "3-2", title = "Moscow Does Not Believe in Tears", start = program.end, end = iso(now + 200 * 60_000))
    private val channel = Channel(id = 3, number = 3, name = "Документальное и научно-популярное", category = "Кино", favorite = true, now = program, next = next)
    private val state = LiveTvState(listOf(channel, channel.copy(id = 4, number = 4)), 3, emptyList(), LiveStream.Ready("x", 1))
    private val allActions = TopBarActions(favorite = true, audio = true, subtitles = true, channels = true, pictureInPicture = true)

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        AntresolTheme { Box(Modifier.fillMaxSize().background(AntresolColors.Background)) { content() } }
    }

    private fun bars() = show {
        Box(Modifier.fillMaxSize()) {
            LiveTopBar(allActions, {}, {}, {}, {}, {}, {}, {}, Modifier.align(Alignment.TopCenter))
            Box(Modifier.align(Alignment.BottomCenter)) { InfoBar(state) }
        }
    }

    /** The screen in pixels (a sheet is its own window, so there may be two roots). */
    private fun screen(): Rect {
        val metrics = compose.activity.resources.displayMetrics
        return Rect(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    /** Every laid-out node lies inside the screen horizontally. */
    private fun assertNothingOverflows() {
        val root = screen()
        val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) || it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) || it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }
        nodes.forEach { node ->
            val b: Rect = node.boundsInRoot
            if (b.width <= 0f) return@forEach
            assertTrue("node ${node.config} at $b runs past the screen $root", b.left >= root.left - 0.5f && b.right <= root.right + 0.5f)
        }
    }

    private fun capture(name: String) {
        val dir = System.getenv("ANTRESOL_SHOTS_DIR") ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun playerBarsFitA360dpPortraitScreen() {
        bars()
        compose.onNodeWithTag("live-more").assertExists()
        compose.onNodeWithTag("live-audio").assertDoesNotExist()
        assertNothingOverflows()
        capture("robolectric-player-bars-360dp-portrait")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun playerBarsFitASmallPhoneInLandscape() {
        bars()
        compose.onNodeWithTag("live-audio").assertExists()
        compose.onNodeWithTag("live-pip").assertExists()
        assertNothingOverflows()
        capture("robolectric-player-bars-640x360dp-landscape")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w997dp-h448dp-land")
    fun theProgressLineSpansALargePhoneInLandscape() {
        bars()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("live-progress", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        // The bar's 24 dp side padding is the only space left beside the line.
        assertEquals(root.width - 48 * density, line.width, density)
        assertNothingOverflows()
        capture("robolectric-player-bars-997x448dp-landscape")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun aListRowIsNumberPictureNameProgrammeAndProgress() {
        show {
            Column { ChannelRow(channel, now, onClick = {}, onToggleFavorite = {}, last = true) }
        }
        compose.onAllNodesWithText(channel.name).assertCountEquals(1)
        compose.onAllNodesWithText(program.title).assertCountEquals(1)
        // No times and no «next» in a list row.
        compose.onAllNodesWithText(next.title, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("until", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("до ", substring = true).assertCountEquals(0)
        assertNothingOverflows()
        capture("robolectric-list-row-360dp")
    }

    /** Round 5: eight audio renditions with long names, the case of «Много голосов». */
    private val eightVoices = listOf(
        "Русский — Дубляж", "Русский — LostFilm", "Русский — Кубик в Кубе", "Русский — Кураж-Бамбей",
        "Русский — LostFilm (очищено)", "Оригинал (English)", "Français", "English — Commentary by the director and the whole cast",
    )

    private fun trackSheet(selected: Int = 1) = show {
        LiveTrackSheet("Audio", eightVoices.mapIndexed { i, name -> TrackOption(name, i == selected) {} }, onDismiss = {})
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun theTickedLastVoiceOpensInView() {
        trackSheet(selected = 7)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val root = screen()
        val b = compose.onNodeWithText(eightVoices[7], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the ticked voice at $b is not on the screen $root", b.height > 0f && b.top >= root.top && b.bottom <= root.bottom + 0.5f)
    }

    /** Every choice can be scrolled to and then lies inside the screen, both ways. */
    private fun assertEveryChoiceReachable() {
        val root = screen()
        eightVoices.forEach { name ->
            compose.onNodeWithTag("live-track-list").performScrollToNode(androidx.compose.ui.test.hasText(name))
            compose.waitForIdle()
            val b = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$name at $b is off the screen $root", b.height > 0f && b.top >= root.top - 0.5f && b.bottom <= root.bottom + 0.5f && b.left >= root.left && b.right <= root.right + 0.5f)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun eightAudioRenditionsScrollInTheSheetOnASmallLandscapePhone() {
        trackSheet()
        compose.waitForIdle()
        assertEveryChoiceReachable()
        assertNothingOverflows()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun eightAudioRenditionsFitA360dpPortraitScreen() {
        trackSheet()
        compose.waitForIdle()
        assertEveryChoiceReachable()
        assertNothingOverflows()
        capture("robolectric-track-sheet-360dp")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun aListRowWithALogoStillFits360dp() {
        val withLogo = channel.copy(logoUrl = "/api/channels/3/logo.png?v=1")
        show {
            androidx.compose.runtime.CompositionLocalProvider(app.antresol.ui.channels.LocalReserveChannelLogo provides true) {
                Column { ChannelRow(withLogo, now, onClick = {}, onToggleFavorite = {}) }
            }
        }
        compose.onAllNodesWithText(channel.name).assertCountEquals(1)
        assertNothingOverflows()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun namesLineUpWhenSomeChannelOfTheListHasALogo() {
        val withLogo = channel.copy(id = 5, number = 5, name = "Кино", logoUrl = "/api/channels/5/logo.png?v=1")
        val plain = channel.copy(id = 6, number = 6, name = "Драмы", logoUrl = null)
        show {
            androidx.compose.runtime.CompositionLocalProvider(
                app.antresol.ui.channels.LocalReserveChannelLogo provides app.antresol.ui.channels.reserveLogoSlot(listOf(withLogo.logoUrl, plain.logoUrl)),
            ) {
                Column {
                    ChannelRow(withLogo, now, onClick = {}, onToggleFavorite = {})
                    ChannelRow(plain, now, onClick = {}, onToggleFavorite = {})
                }
            }
        }
        compose.waitForIdle()
        // The row without a logo keeps an empty logo column, so names and programmes start at the same x.
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("channel-logo-slot"), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("no reserved slot", it.isNotEmpty())
        }
        val a = compose.onNodeWithText("Кино", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("Драмы", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(a.left, b.left, 0.5f)
        assertNothingOverflows()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun aListWithoutLogosReservesNothing() {
        show { Column { ChannelRow(channel, now, onClick = {}, onToggleFavorite = {}) } }
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("channel-logo-slot"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) =
        assertEquals(n, fetchSemanticsNodes().size)


}
