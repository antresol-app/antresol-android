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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.core.channels.Channels
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
    private val others = listOf(
        channel.copy(id = 1, number = 1, name = "All Series", favorite = false, logoUrl = null),
        channel.copy(id = 4, number = 4, name = "Comedy", favorite = false),
        channel.copy(id = 5, number = 5, name = "Kids", favorite = false),
        channel.copy(id = 6, number = 6, name = "Music", favorite = false),
    )
    private val state = LiveTvState(listOf(others[0], channel) + others.drop(1), 3, emptyList(), LiveStream.Ready("x", 1))
    private fun ui(fullscreen: Boolean) = LiveControlsUi(fullscreen = fullscreen, paused = false, centre = true, behindLive = false, tracks = true, channels = true, pictureInPicture = true)

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        AntresolTheme { Box(Modifier.fillMaxSize().background(AntresolColors.Background)) { content() } }
    }

    /** A stand-in for the picture (a dusk sea). */
    @Composable
    private fun Picture(modifier: Modifier) = Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF1B3A5C), Color(0xFF3F6E8C), Color(0xFFB88A5A), Color(0xFF173247)))))

    @Composable
    private fun Controls(fullscreen: Boolean, s: LiveTvState = state, u: LiveControlsUi = ui(fullscreen)) =
        LiveControls(s, u, {}, {}, {}, {}, {}, {}, {}, {})

    /** The portrait player: the picture with its controls, the details and the list under it. */
    private fun portrait(controls: Boolean = true, s: LiveTvState = state) = show {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Picture(Modifier.fillMaxSize())
                if (controls) Controls(fullscreen = false, s = s)
            }
            LiveBelow(s, Channels.miniGuide(s.listed, s.channelId), behindLive = false, onGoLive = {}, onFavorite = {}, onTune = {}, modifier = Modifier.weight(1f))
        }
    }

    private fun bars() = show {
        Box(Modifier.fillMaxSize()) {
            Picture(Modifier.fillMaxSize())
            Controls(fullscreen = true)
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
    fun thePortraitPlayerFitsA360dpScreen() {
        portrait()
        compose.onNodeWithTag("live-tracks").assertExists()
        compose.onNodeWithTag("live-fullscreen").assertExists()
        // The guide button and the star are full-screen controls: in portrait the list and the star are below.
        compose.onNodeWithTag("live-guide").assertDoesNotExist()
        compose.onAllNodesWithTag("live-favorite").assertCountEquals(1)
        assertNothingOverflows()
        capture("portrait-360dp")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
    fun portraitShot() {
        portrait()
        assertNothingOverflows()
        capture("portrait")
    }

    /** The header's star follows the name and its «LIVE» (not the row's end); a long name gives way first. */
    private fun starFollowsTheName(name: String, label: String) {
        val named = channel.copy(name = name)
        val s = LiveTvState(listOf(others[0], named) + others.drop(1), 3, emptyList(), LiveStream.Ready("x", 1))
        portrait(controls = false, s = s)
        val star = compose.onNodeWithTag("live-favorite", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("3 · $name", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        // between the name and the star there is only the badge («Прямой эфир» is the wider, about 100 dp) and the row's gaps
        val gap = (star.left - title.right) / density
        assertTrue("the star is ${gap}dp after the name's end", gap in 0f..150f)
        assertTrue("the star at $star is off the screen ${screen()}", star.right <= screen().right + 0.5f)
        assertNothingOverflows()
        capture("star-$label")
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp-port-xhdpi")
    fun theStarFollowsAShortNameEn() = starFollowsTheName("Billions", "en-normal")

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp-port-xhdpi")
    fun theStarFollowsALongNameEn() = starFollowsTheName("The Extraordinary Adventures of Wildlife and Documentary Entertainment HD", "en-long")

    @Test
    @Config(sdk = [35], qualifiers = "ru-w411dp-h891dp-port-xhdpi")
    fun theStarFollowsAShortNameRu() = starFollowsTheName("Комедии", "ru-normal")

    @Test
    @Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-port")
    fun theStarFollowsALongNameRu() = starFollowsTheName("Международный информационно-развлекательный познавательный телеканал Первый", "ru-long")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
    fun portraitStartingShot() {
        show {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spinner()
                        Text(stringResource(app.antresol.ui.R.string.tv_starting))
                    }
                }
                LiveBelow(state, Channels.miniGuide(state.listed, state.channelId), behindLive = true, onGoLive = {}, onFavorite = {}, onTune = {}, modifier = Modifier.weight(1f))
            }
        }
        capture("portrait-starting-behind-live")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xhdpi")
    fun landscapeShot() {
        bars()
        compose.onNodeWithTag("live-guide").assertExists()
        assertNothingOverflows()
        capture("landscape-controls")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xhdpi")
    fun landscapeSideGuideShot() {
        show {
            Box(Modifier.fillMaxSize()) {
                Picture(Modifier.fillMaxSize())
                SideGuide(Channels.miniGuide(state.listed, state.channelId), "All Channels", state.channelId, {}, {}, {})
            }
        }
        compose.onNodeWithTag("live-side-guide").assertExists()
        assertNothingOverflows()
        capture("landscape-side-guide")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun playerBarsFitASmallPhoneInLandscape() {
        bars()
        compose.onNodeWithTag("live-tracks").assertExists()
        compose.onNodeWithTag("live-pip").assertExists()
        compose.onNodeWithTag("live-guide").assertExists()
        assertNothingOverflows()
        capture("landscape-640x360dp")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w997dp-h448dp-land")
    fun theProgressLineSpansALargePhoneInLandscape() {
        bars()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("live-progress", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        // The line takes what LIVE and the full-screen button leave: most of the width.
        assertTrue("the line is ${line.width} of ${root.width}", line.width > root.width * 0.7f)
        assertNothingOverflows()
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
        capture("list-row-360dp")
    }

    /** Round 5: eight audio renditions with long names, the case of «Много голосов». */
    private val eightVoices = listOf(
        "Русский — Дубляж", "Русский — LostFilm", "Русский — Кубик в Кубе", "Русский — Кураж-Бамбей",
        "Русский — LostFilm (очищено)", "Оригинал (English)", "Français", "English — Commentary by the director and the whole cast",
    )

    private fun trackSheet(selected: Int = 1) = show {
        LiveTrackSheet(
            listOf(
                TrackSection("Audio", eightVoices.mapIndexed { i, name -> TrackOption(name, i == selected) {} }),
                TrackSection("Subtitles", listOf(TrackOption("Off", selected != 7) {}, TrackOption("Русские", false) {})),
            ),
            onDismiss = {},
        )
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
        capture("track-sheet-360dp")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun aListRowWithALogoStillFits360dp() {
        val withLogo = channel.copy(logoUrl = "/api/channels/3/logo.png?v=1")
        show { Column { ChannelRow(withLogo, now, onClick = {}, onToggleFavorite = {}) } }
        compose.onAllNodesWithText(channel.name).assertCountEquals(1)
        assertNothingOverflows()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun namesLineUpWithTheInitialsOfAChannelWithoutALogo() {
        val withLogo = channel.copy(id = 5, number = 5, name = "Кино", logoUrl = "/api/channels/5/logo.png?v=1")
        val plain = channel.copy(id = 6, number = 6, name = "Драмы", logoUrl = null)
        show {
            Column {
                ChannelRow(withLogo, now, onClick = {}, onToggleFavorite = {})
                ChannelRow(plain, now, onClick = {}, onToggleFavorite = {})
            }
        }
        compose.waitForIdle()
        // The row without a logo shows the channel's initials in the logo's box (as the web), never an empty one
        // (a logo that cannot load, as here without a server, gives way to them too).
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("channel-initials"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("channel-logo-slot"), useUnmergedTree = true).assertCountEquals(0)
        val a = compose.onNodeWithText("Кино", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("Драмы", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(a.left, b.left, 0.5f)
        assertNothingOverflows()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
    fun aRowWithoutALogoOrAPictureHasNoEmptyBox() {
        val bare = channel.copy(logoUrl = null, logoAlt = null, now = null, next = null)
        show { Column { ChannelRow(bare, now, onClick = {}, onToggleFavorite = {}) } }
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("channel-logo-slot"), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("channel-initials"), useUnmergedTree = true).assertCountEquals(1)
        // No programme picture: no still (its placeholder box) in the row.
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("programme-still"), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) =
        assertEquals(n, fetchSemanticsNodes().size)


}
