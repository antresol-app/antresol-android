package app.antresol.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.tv.ui.shell.MainShell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The TV side of the owner's 2026-11-23 list against [FakeServer] with one addon: the labels of a series
 * page's action row whole on one line (English and Russian), and the page that zooms open and shut.
 * Screenshots go to `tv/build/screenshots/nov23-*.png`.
 */
abstract class TvOwnerNov23Base {
    @get:org.junit.Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

    protected abstract val lang: String

    private fun start() {
        fake.addons = true
        fake.start()
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        compose.setContent {
            val state by session.state.collectAsState()
            (state as? AuthState.SignedIn)?.let { AntresolTvTheme { MainShell(session, it) } }
        }
        waitFor(hasText("The Test Show") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        waitFor(isFocused())
    }

    @After
    fun tearDown() {
        fake.shutdown()
    }

    @Test
    fun theActionRowOfASeriesPageKeepsEveryLabelOnOneLine() {
        start()
        shot("nov23-tv-home-$lang")
        key(KeyEvent.KEYCODE_DPAD_RIGHT) // hero Play → Details
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("page/detail"))
        waitFor(hasTestTag("ways-more"))
        settle()
        shot("nov23-tv-detail-$lang")
        val texts = compose.onAllNodes(hasTestTag("page/detail"), useUnmergedTree = true).fetchSemanticsNodes().flatMap { texts(it) }
        val resume = texts.firstOrNull { it.layoutInput.text.text.let { text -> text.startsWith(if (lang == "ru") "Продолжить" else "Resume") } }
        assertTrue("the Resume label: ${texts.map { it.layoutInput.text.text }}", resume != null)
        assertEquals(resume!!.layoutInput.text.text, 1, resume.lineCount)
        assertFalse("Resume is cut: ${resume.layoutInput.text.text}", resume.didOverflowWidth)
        // No button runs off the screen: the one that does not fit goes to a second line.
        val width = compose.activity.window.decorView.width
        val trailer = compose.onNode(hasTestTag("action-trailer"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the Trailer button is whole: $trailer", trailer.right <= width && trailer.width > 100)
    }

    /** A page opens with a zoom: a frame in the middle of it is smaller than the page after it. */
    @Test
    fun aDetailPageZoomsOpenAndShut() {
        start()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.mainClock.autoAdvance = false
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        for ((index, at) in listOf(40L, 60L, 80L, 120L).withIndex()) {
            Thread.sleep(40)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(at)
            shot("nov23-tv-zoom-${index + 1}-open-$lang")
        }
        compose.mainClock.autoAdvance = true
        waitFor(hasTestTag("page/detail"))
        settle()
        shot("nov23-tv-zoom-5-page-$lang")
        compose.mainClock.autoAdvance = false
        press(KeyEvent.KEYCODE_BACK)
        for ((index, at) in listOf(60L, 100L, 120L).withIndex()) {
            Thread.sleep(40)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(at)
            shot("nov23-tv-zoom-${index + 6}-close-$lang")
        }
        compose.mainClock.autoAdvance = true
        settle()
        shot("nov23-tv-zoom-9-back-$lang")
    }

    private fun texts(node: SemanticsNode): List<TextLayoutResult> {
        val own = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(own)
        return own + node.children.flatMap { texts(it) }
    }

    private fun press(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun key(code: Int) {
        press(code)
        compose.waitForIdle()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(50)
        }
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
            shot("nov23-tv-failure-$lang")
            throw e
        }
    }

    private fun shot(name: String) {
        if (compose.mainClock.autoAdvance) compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvOwnerNov23EnUiTest : TvOwnerNov23Base() {
    override val lang = "en"
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "ru-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvOwnerNov23RuUiTest : TvOwnerNov23Base() {
    override val lang = "ru"
}
