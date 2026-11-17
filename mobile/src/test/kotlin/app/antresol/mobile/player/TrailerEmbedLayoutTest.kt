package app.antresol.mobile.player

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.ui.trailers.TrailerEmbed
import app.antresol.ui.trailers.TrailerTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Nothing of the app lies over the embedded player: the back button has its own strip beside or above it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrailerEmbedLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    /** The player's and the back button's rectangles (px in the window) once the player reports playing. */
    private fun playing(): Pair<Rect, Rect> {
        compose.setContent { TrailerEmbedScreen("movieTeaser2", "Teaser", onExit = {}) }
        compose.waitForIdle()
        val web = findWebView(compose.activity.window.decorView) ?: error("no WebView")
        val bridge = shadowOf(web).getJavascriptInterface(TrailerEmbed.BRIDGE)
        bridge.javaClass.getMethod("onState", Int::class.javaPrimitiveType).invoke(bridge, 1)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        val density = Density(compose.activity)
        fun bounds(node: SemanticsNodeInteraction) = with(density) {
            val bounds = node.getBoundsInRoot()
            Rect(bounds.left.toPx(), bounds.top.toPx(), bounds.right.toPx(), bounds.bottom.toPx())
        }
        val player = bounds(compose.onNodeWithTag(TrailerTags.EMBED))
        val back = bounds(compose.onNodeWithContentDescription("Back"))
        assertTrue(player.width > 0 && player.height > 0)
        assertFalse("back $back over the player $player", back.overlaps(player))
        return player to back
    }

    @Test
    @Config(qualifiers = "en-w800dp-h360dp-land-xxhdpi")
    fun inLandscapeTheBackButtonIsBesideThePlayer() {
        val (player, back) = playing()
        assertTrue(back.right <= player.left)
        // The player keeps the full height, 16:9, in the middle of what the strip leaves.
        assertEquals(0f, player.top)
        assertEquals(360 * 3f, player.height)
        assertEquals(640 * 3f, player.width)
        assertEquals((56 + (744 - 640) / 2) * 3f, player.left)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp-port-xxhdpi")
    fun inPortraitTheBackButtonIsAboveThePlayer() {
        val (player, back) = playing()
        assertTrue(back.bottom <= player.top)
        // The full width, 16:9, in the middle of what is under the strip.
        assertEquals(0f, player.left)
        assertEquals(360 * 3f, player.width)
        assertEquals(16f / 9f, player.width / player.height, 0.01f)
        assertEquals((56 * 3 + 800 * 3) / 2f, player.center.y, 2f)
    }
}
