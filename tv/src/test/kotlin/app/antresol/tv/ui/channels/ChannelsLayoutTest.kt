package app.antresol.tv.ui.channels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.api.MediaUrls
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelList
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.shell.NavTab
import app.antresol.tv.ui.shell.TopNav
import app.antresol.ui.channels.ChannelsApi
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.theme.AntresolColors
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The «ТВ → Каналы» page: the list on a panel (left ~46%), the card of the focused programme beside it,
 * both filling the page under the header, the category button next to the view switch. Sizes are checked in
 * canvas dp (px × 960 / window width), so the same page must come out the same at 960 dp (xhdpi, the design
 * canvas) and at 1442 dp (tvdpi, what many 1080p TVs report). Screenshots go to `$CHANNEL_SHOTS_DIR`
 * (default `tv/build/channel-shots`).
 */
abstract class ChannelsLayoutBase(private val density: String) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File(System.getenv("CHANNEL_SHOTS_DIR") ?: "build/channel-shots").also { it.mkdirs() }
    private val nowMs = System.currentTimeMillis()
    private val watched = ArrayList<Pair<Long, ChannelFilter>>()

    @Before
    fun fakeImages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                            val n = Regex("still-(\\d+)").find(chain.request.data.toString())?.groupValues?.get(1)?.toIntOrNull()
                                ?: return chain.proceed(chain.request)
                            return SuccessResult(drawable = BitmapDrawable(context.resources, fakeStill(n)), request = chain.request, dataSource = DataSource.MEMORY)
                        }
                    })
                }
                .build(),
        )
    }

    /** The page for [count] channels, focus starting on the channel with [lastIndex]; [withNav] adds the top navigation (for screenshots). */
    private fun show(count: Int, lastIndex: Int = if (count > 3) 2 else 0, withNav: Boolean = false): ChannelsViewModel {
        val channels = fakeChannels(count, nowMs)
        val api = object : ChannelsApi {
            override suspend fun channels() = ChannelList(channels, lastChannel = channels[lastIndex].id)
            override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit
            override suspend fun epg(fromIso: String, hours: Int) = ChannelEpg()
        }
        val viewModel = ChannelsViewModel(api)
        compose.setContent {
            CompositionLocalProvider(LocalMediaUrls provides MediaUrls("http://fake.local")) {
                AntresolTvTheme {
                    Box(Modifier.fillMaxSize().background(AntresolColors.Background)) {
                        ChannelsScreen(viewModel, onWatch = { channel, filter -> watched += channel.id to filter }, onScrolledToTop = {}, canTakeFocus = { true })
                        if (withNav) {
                            TopNav(
                                selected = NavTab.Tv,
                                contextTab = null,
                                userName = "Anton",
                                avatar = "grad-3",
                                photoUrl = null,
                                nowPlaying = null,
                                nowPlayingActive = false,
                                onSelect = {},
                                onNowPlaying = {},
                                onSettings = {},
                                onFocusChanged = {},
                                onEnterContent = { false },
                            )
                        }
                    }
                }
            }
        }
        waitFor { viewModel.channels != null && compose.onAllNodes(hasTestTag("tv/channel-card")).fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        settle()
        return viewModel
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000) {
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        condition()
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.waitForIdle()
        }
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
        Thread.sleep(60)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        settle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir, "$density-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [node]'s bounds in canvas dp. */
    private fun SemanticsNodeInteraction.canvas(): Rect = canvas(fetchSemanticsNode().boundsInRoot)

    private fun canvas(r: Rect): Rect {
        val scale = compose.activity.window.decorView.width / 960f
        return Rect(r.left / scale, r.top / scale, r.right / scale, r.bottom / scale)
    }

    private fun canvasHeight() = compose.activity.window.decorView.height / (compose.activity.window.decorView.width / 960f)

    private val rowMatcher get() = hasClickAction() and hasContentDescription(" · ", substring = true)

    /** The channel rows composed now, in canvas dp. */
    private fun rows(): List<Rect> = compose.onAllNodes(rowMatcher).fetchSemanticsNodes().map { canvas(it.boundsInRoot) }

    private fun button() = compose.onNode(hasContentDescription("Channels to show", substring = true), useUnmergedTree = true)

    private fun switch() = compose.onNode(hasContentDescription("View"), useUnmergedTree = true)

    /** Every text on the card. */
    private fun cardTexts(): List<String> {
        fun texts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()) + node.children.flatMap(::texts)
        return texts(compose.onNodeWithTag("tv/channel-card", useUnmergedTree = true).fetchSemanticsNode())
    }

    /** The programme the card shows (its title is a text of its own, a row's is a whole programme line). */
    private fun cardTitle(): String = cardTexts().first { text -> shows.any { it.title == text } }

    private fun focusedRow(): String? = compose.onAllNodes(isFocused() and rowMatcher).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    @Test
    fun theListPanelAndTheCardSplitTheWidthAndFillTheHeight() {
        show(3)
        val list = compose.onNodeWithTag("tv/channel-list").canvas()
        val card = compose.onNodeWithTag("tv/channel-card").canvas()
        assertEquals("list left", 48f, list.left, 1.5f)
        // 46% of what is left of the page's width after the gap.
        assertEquals("list width", (960f - 96f - 24f) * 0.46f, list.width, 2f)
        assertEquals("card starts after the gap", list.right + 24f, card.left, 1.5f)
        assertEquals("card right", 960f - 48f, card.right, 1.5f)
        assertEquals("same top", list.top, card.top, 1f)
        assertEquals("same bottom", list.bottom, card.bottom, 1f)
        // The lower part of the page is not empty: both reach the bottom margin.
        assertEquals("bottom margin", canvasHeight() - 28f, list.bottom, 2f)
        assertTrue("right under the header: ${list.top}", list.top in 130f..150f)
    }

    @Test
    fun fewChannelsMakeTallRows() {
        show(3)
        val tall = rows()
        assertEquals(3, tall.size)
        tall.forEach { assertTrue("tall row ${it.height}", it.height in 84f..105f) }
    }

    @Test
    fun manyChannelsMakeCompactRowsThatScrollInThePanel() {
        show(12)
        val compact = rows()
        assertTrue("rows composed: ${compact.size}", compact.size in 4..8)
        // (A row cut by the panel's edge is shorter.)
        assertTrue("compact rows: ${compact.map { it.height }}", compact.count { Math.abs(it.height - 62f) < 2f } >= 4 && compact.all { it.height < 64f })
    }

    @Test
    fun theCategoryButtonSitsRightNextToTheViewSwitch() {
        show(3)
        val switch = switch().canvas()
        val button = button().canvas()
        assertEquals("a gap of 16 dp after the switch", switch.right + 16f, button.left, 2f)
        assertEquals("on its line", switch.center.y, button.center.y, 3f)
        assertTrue("not pushed to the far edge: ${button.right}", button.right < 960f / 2)
    }

    @Test
    fun theCardFollowsFocusAsDownAndUpWalkTheList() {
        show(12)
        // Focus starts on the channel the profile watched last (the third).
        assertEquals("Planet Earth II", cardTitle())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Orbital", cardTitle())
        key(KeyEvent.KEYCODE_DPAD_UP, times = 2)
        assertEquals("Harbor Lights", cardTitle())
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Billions", cardTitle())
    }

    @Test
    fun downToTheEndScrollsTheListInsideItsPanelAndTheHeaderStays() {
        show(12)
        val buttonBefore = button().canvas()
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 9)
        assertEquals("Little Explorers", cardTitle())
        val list = compose.onNodeWithTag("tv/channel-list").canvas()
        val focused = compose.onNode(isFocused() and rowMatcher).canvas()
        assertTrue("the focused row is inside the panel: $focused in $list", focused.top >= list.top && focused.bottom <= list.bottom + 1f)
        assertEquals("the header stays", buttonBefore.top, button().canvas().top, 0.5f)
        // Down from the last channel stays on it.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Little Explorers", cardTitle())
        // And back up to the first.
        key(KeyEvent.KEYCODE_DPAD_UP, times = 11)
        assertEquals("Billions", cardTitle())
    }

    @Test
    fun upFromTheFirstChannelReachesTheHeader() {
        show(12)
        key(KeyEvent.KEYCODE_DPAD_UP, times = 3)
        assertTrue("something has focus", compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
        assertTrue("it is a header control, not a channel", compose.onAllNodes(isFocused() and rowMatcher).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun downFromTheHeaderComesBackToTheChannelTheCardShows() {
        show(12)
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
        assertEquals("Night Desk", cardTitle())
        button().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertTrue("the category button has focus", compose.onAllNodes(isFocused() and rowMatcher).fetchSemanticsNodes().isEmpty())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("on the fifth channel: ${focusedRow()}", focusedRow()?.startsWith("5 · Thriller") == true)
        assertEquals("Night Desk", cardTitle())
    }

    @Test
    fun okWatchesTheChannelTheCardShows() {
        show(12)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(4L to ChannelFilter.All), watched)
    }

    @Test
    fun theGuideKeepsTheButtonNextToTheSwitch() {
        show(3)
        compose.onNode(hasText("Guide") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor { compose.onAllNodes(hasTestTag("tv/channel-card")).fetchSemanticsNodes().isEmpty() }
        val switch = switch().canvas()
        val button = button().canvas()
        assertEquals(switch.right + 16f, button.left, 2f)
        assertEquals(switch.center.y, button.center.y, 3f)
    }

    @Test
    fun screenshotOfThreeChannels() {
        show(3, withNav = true)
        shot("3")
    }

    @Test
    fun screenshotOfTwelveChannels() {
        show(12, withNav = true)
        shot("12")
    }

    @Test
    fun screenshotOfTwelveChannelsScrolledToTheEnd() {
        show(12, withNav = true)
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 9)
        shot("12-end")
    }
}

/** The design canvas: 960x540 dp at xhdpi (1920x1080 px). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelsLayoutXhdpiTest : ChannelsLayoutBase("xhdpi")

/** A common 1080p TV setting: tvdpi (213 dpi), so the screen reports about 1442x811 dp. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w1442dp-h811dp-land-television-tvdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelsLayoutTvdpiTest : ChannelsLayoutBase("tvdpi")
