package app.antresol.ui.trailers

import android.os.Looper
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The embedded trailer's WebView when the app leaves and comes back, and while it plays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrailerEmbedStateTest {
    private val state = TrailerEmbedState("movieTeaser2")
    private val view = WebView(RuntimeEnvironment.getApplication()).also {
        state.webView = it
        state.load(it)
    }
    private val shadow = shadowOf(view)

    private fun report(playerState: Int) {
        state.bridge.onState(playerState)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aTrailerLeftWhileLoadingCannotStartInTheBackground() {
        val first = shadow.lastLoadDataWithBaseURL
        state.onStop()
        // No player to pause yet: the page is dropped, so nothing starts playing behind the launcher.
        assertEquals("about:blank", shadow.lastLoadedUrl)
        assertNull(shadow.lastEvaluatedJavascript)
        assertTrue(shadow.wasOnPauseCalled())
        // What the dropped page still reports is not shown, nor its cancelled loads as an error.
        report(1)
        state.onPageFailed()
        assertEquals(EmbedPhase.Loading, state.phase)
        // Back in front it is loaded again (and starts).
        state.onStart()
        assertNotSame(first, shadow.lastLoadDataWithBaseURL)
        report(1)
        assertEquals(EmbedPhase.Playing, state.phase)
    }

    @Test
    fun aPageThatDoesNotLoadIsAnError() {
        state.onPageFailed()
        assertEquals(EmbedPhase.Failed(-2), state.phase)
        assertEquals(false, view.keepScreenOn)
    }

    @Test
    fun aPlayingTrailerIsPausedInTheBackground() {
        report(1)
        state.onStop()
        assertEquals(TrailerEmbed.PAUSE_JS, shadow.lastEvaluatedJavascript)
        assertTrue(shadow.wasOnPauseCalled())
        assertEquals(null, shadow.lastLoadedUrl)
    }

    @Test
    fun theScreenIsKeptOnOnlyWhileTheTrailerPlays() {
        report(1)
        assertTrue(view.keepScreenOn)
        report(2) // paused
        assertEquals(false, view.keepScreenOn)
        report(3) // buffering: about to play
        assertTrue(view.keepScreenOn)
        report(0) // ended
        assertEquals(false, view.keepScreenOn)
        report(1)
        state.bridge.onError(150)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, view.keepScreenOn)
    }
}
