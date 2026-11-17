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

    /** The page of load [load] (the current one unless said) reports the player's state. */
    private fun report(playerState: Int, load: Int = state.loads) {
        state.bridge.onState(load, playerState)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The page of load [load] runs its first script. */
    private fun pageRuns(load: Int = state.loads) {
        state.bridge.onPage(load)
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
    fun whatADroppedPageReportsAfterTheReturnIsNotShown() {
        pageRuns()
        state.onStop()
        state.onStart()
        assertEquals(2, state.loads)
        assertTrue("movieTeaser2" in shadow.lastLoadDataWithBaseURL.data && "onPage(2)" in shadow.lastLoadDataWithBaseURL.data)
        // The first page's cancelled loads and its player's last words arrive only now (stop and
        // start close together): the trailer that is loading stays, no error page over it.
        state.onPageFailed()
        state.bridge.onError(1, 5)
        report(0, load = 1)
        assertEquals(EmbedPhase.Loading, state.phase)
        // The new page runs: from now on a failed load is its own.
        pageRuns(load = 1)
        state.onPageFailed()
        assertEquals(EmbedPhase.Loading, state.phase)
        pageRuns()
        state.onPageFailed()
        assertEquals(EmbedPhase.Failed(-2), state.phase)
        // «Try again» loads once more; the page before it is not listened to.
        state.reload()
        report(1, load = 2)
        assertEquals(EmbedPhase.Loading, state.phase)
        report(1)
        assertEquals(EmbedPhase.Playing, state.phase)
    }

    @Test
    fun aPageThatDoesNotLoadIsAnError() {
        pageRuns()
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
        state.bridge.onError(state.loads, 150)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, view.keepScreenOn)
    }
}
