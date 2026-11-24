package app.antresol.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.mobile.ui.components.LocalPosterZoom
import app.antresol.mobile.ui.shell.MainShell
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The phone side of the owner's 2026-11-23 list against [FakeServer] with one addon: the labels of the
 * detail action row on one line at 360 dp (English and Russian), the badges of a source row, the poster
 * that zooms into its page and back. Screenshots go to `mobile/build/screenshots/nov23-*.png`.
 */
abstract class PhoneOwnerNov23Base {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

    /** "en" or "ru": the suffix of the screenshots and the words to look for. */
    protected abstract val lang: String

    /**
     * Signs in and shows the shell. [zoom] is the poster zoom: off for the tests that take screenshots (they draw the view
     * on a software canvas, which the zoom's hardware layers cannot be drawn on).
     */
    private fun start(zoom: Boolean) {
        fake.addons = true
        fake.start()
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        val state = session.state.value as AuthState.SignedIn
        // No crossfade: Robolectric draws no animation frames, a first load would stay an empty frame.
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        compose.setContent { CompositionLocalProvider(LocalPosterZoom provides zoom) { AntresolTheme { MainShell(session, state) } } }
        // The shelves' titles are the server's, in English here whatever the app's language.
        waitFor(hasText("Continue Watching"))
    }

    @After
    fun tearDown() = fake.shutdown()

    private fun openSeries() {
        start(zoom = false)
        list().performScrollToNode(hasText("New Series"))
        compose.onAllNodes(hasText("The Test Show"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("detail-play"))
        waitFor(hasTestTag("ways-more"))
        settle()
    }

    /** The series page's play row at 360 dp: Resume with the restart, trailer and ways buttons beside it. */
    @Test
    fun theDetailActionRowKeepsEveryLabelOnOneLine() {
        openSeries()
        shot("nov23-detail-actions-$lang")
        for (tag in listOf("detail-play", "toggle-watchlist", "toggle-watched")) {
            for (layout in textLayouts(tag)) {
                assertEquals("$tag wraps: ${layout.layoutInput.text}", 1, layout.lineCount)
                assertFalse("$tag is cut: ${layout.layoutInput.text}", layout.isLineEllipsized(0))
            }
        }
        // The main button says the whole word, with the episode.
        val label = textLayouts("detail-play").single().layoutInput.text.text
        assertTrue(label, label.endsWith("S1 E2"))
        assertTrue(label, label.startsWith(if (lang == "ru") "Продолжить" else "Resume"))
    }

    /** The source rows of the choice step by step: every badge as tall as the others, «Subtitles» with its word. */
    @Test
    fun theSourceRowsHaveBadgesOfOneSize() {
        openSeries()
        compose.onNode(hasTestTag("ways-more")).performClick()
        waitFor(hasTestTag("way-watch"))
        compose.onNode(hasTestTag("way-watch")).performClick()
        waitFor(hasTestTag("way-step-sheet"))
        waitFor(hasText("Source One"))
        settle()
        shotAll("nov23-step-sheet-$lang")
        val inSheet = hasAnyAncestor(hasTestTag("way-step-sheet"))
        val heights = listOf("HD", "Audio RU EN", "Subtitles").map { badge ->
            compose.onNode(hasContentDescription(badge) and inSheet, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        }
        assertEquals(heights.toString(), 1, heights.map { Math.round(it) }.distinct().size)
        // «Subtitles» is the icon and its word, wider than a bare icon in a chip.
        val subtitles = compose.onNode(hasContentDescription("Subtitles") and inSheet, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the subtitles badge is only an icon: ${subtitles.width} x ${subtitles.height}", subtitles.width > 3 * subtitles.height)
    }

    /**
     * The poster zooms from its tile into the page: halfway through the open the shared artwork is bigger than the tile and
     * smaller than the page's, the page's artwork has the tile's key, and on Back the artwork shrinks again. The frames are
     * saved as screenshots (drawn through a hardware renderer, as the zoom's layers need one).
     */
    @Test
    fun thePosterZoomsIntoThePageAndBack() {
        start(zoom = true)
        list().performScrollToNode(hasText("New Series"))
        settle()
        hardwareShot("nov23-zoom-0-home-$lang")
        val tileWidth = posterWidths().single()
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasText("The Test Show")).onLast().performClick()
        val opening = mutableListOf<Float>()
        for ((index, at) in listOf(60L, 100L, 100L, 120L).withIndex()) {
            compose.mainClock.advanceTimeBy(at)
            Thread.sleep(60)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            hardwareShot("nov23-zoom-${index + 1}-open-$lang")
            opening += posterWidths()
        }
        compose.mainClock.autoAdvance = true
        waitFor(hasTestTag("detail-play"))
        settle()
        hardwareShot("nov23-zoom-5-page-$lang")
        val keys = posterKeys()
        assertTrue("the page's artwork has the tile's key: $keys", keys.any { it.contains("series:") })
        val pageWidth = posterWidths().max()
        // Halfway the artwork is between the tile's size and the page's.
        assertTrue("the artwork grew while opening: tile $tileWidth, page $pageWidth, frames $opening", opening.any { it > tileWidth * 1.2f && it < pageWidth * 0.98f })
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        val closing = mutableListOf<Float>()
        for ((index, at) in listOf(80L, 120L, 120L).withIndex()) {
            compose.mainClock.advanceTimeBy(at)
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            hardwareShot("nov23-zoom-${index + 6}-close-$lang")
            closing += posterWidths()
        }
        compose.mainClock.autoAdvance = true
        assertTrue("the artwork shrank while closing: frames $closing", closing.any { it < pageWidth * 0.98f })
    }

    /** The widths (px) of the artwork nodes that carry the key of the New Series tile. */
    private fun posterWidths(): List<Float> =
        compose.onAllNodes(SemanticsMatcher("the poster of New Series") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("poster/New Series/") == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot.width }

    private fun posterKeys(): List<String> =
        compose.onAllNodes(SemanticsMatcher("a poster") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("poster/") == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /** What the text of the nodes tagged [tag] laid out as. */
    private fun textLayouts(tag: String): List<TextLayoutResult> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().flatMap { collectTexts(it) }
            .ifEmpty { error("no text in $tag") }

    private fun collectTexts(node: androidx.compose.ui.semantics.SemanticsNode): List<TextLayoutResult> {
        val own = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(own)
        return own + node.children.flatMap { collectTexts(it) }
    }

    private fun list() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    private fun settle() {
        repeat(25) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) {
        try {
            compose.waitUntil(timeoutMs) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            shot("nov23-failure-$lang")
            throw e
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The view drawn through a hardware renderer (layers of the poster zoom need one); false when this device cannot.
     */
    private fun hardwareShot(name: String): Boolean = try {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val width = view.width
        val height = view.height
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val node = RenderNode("shot")
        val renderer = HardwareRenderer()
        compose.runOnUiThread {
            node.setPosition(0, 0, width, height)
            view.draw(node.beginRecording(width, height))
            node.endRecording()
        }
        renderer.setContentRoot(node)
        renderer.setSurface(reader.surface)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        val plane = image.planes[0]
        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.close()
        renderer.destroy()
        true
    } catch (e: Throwable) {
        File(shots, "nov23-hardware-shot-unavailable.txt").writeText(e.stackTraceToString())
        false
    }

    /** Like [shot], with the windows of dialogs and sheets drawn over the activity's (a sheet is a window of its own). */
    private fun shotAll(name: String) {
        compose.waitForIdle()
        val activityView = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(activityView.width, activityView.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(bitmap)
            for (root in rootViews()) {
                canvas.save()
                val at = IntArray(2)
                root.getLocationOnScreen(at)
                canvas.translate(at[0].toFloat(), at[1].toFloat())
                root.draw(canvas)
                canvas.restore()
            }
        }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        (field.get(global) as List<View>).filter { it.width > 0 && it.height > 0 }
    }.getOrElse { listOf(compose.activity.window.decorView) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneOwnerNov23EnUiTest : PhoneOwnerNov23Base() {
    override val lang = "en"
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "ru-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneOwnerNov23RuUiTest : PhoneOwnerNov23Base() {
    override val lang = "ru"
}
