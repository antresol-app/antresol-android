package app.antresol.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.channels.MiniGuide
import app.antresol.core.model.Channel
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The player's channel list (TiviMate panel) at the end of a long list: the first row shown is a
 * whole row, not a sliver of the previous one under the title.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
class MiniGuidePanelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val channels = (1..13).map { Channel(id = it.toLong(), number = it, name = "Канал $it", logoUrl = if (it == 13) "/api/channels/13/logo.png?v=1" else null) }

    @Test
    fun everyShownRowIsWholeAtTheEndOfTheList() {
        compose.setContent {
            AntresolTheme { ChannelListPanel(MiniGuide(channels, 12), "Все каналы", currentId = 13, selected = 12) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val list = compose.onNodeWithTag("mini-guide-list").fetchSemanticsNode().boundsInRoot
        val full = compose.onNodeWithText("Канал 13", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        var shown = 0
        channels.forEach { channel ->
            val node = runCatching { compose.onNodeWithText(channel.name, useUnmergedTree = true).fetchSemanticsNode() }.getOrNull() ?: return@forEach
            val b = node.boundsInRoot
            if (b.height <= 0f) return@forEach
            shown++
            assertEquals("${channel.name} is cut by the list's top", full, b.height, 0.5f)
            assertTrue("${channel.name} at $b starts above the list $list", b.top >= list.top)
        }
        assertTrue("too few rows shown: $shown", shown >= 5)
    }
}
