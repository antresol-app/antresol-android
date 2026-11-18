package app.antresol.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.MiniGuide
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import app.antresol.tv.ui.channels.filterTitle
import app.antresol.ui.theme.AntresolTheme
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player's channel list (TiviMate panel) at the end of a long list: the first row shown is a
 * whole row, not a sliver of the previous one under the title; four-digit numbers, the «more» hints
 * and the end time. Shots go to `tv/build/screenshots/mini-guide-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    /** A programme on air now, its times written as the server writes them in a +03:00 zone. */
    private fun onAirNow(title: String, end: String? = null): ChannelProgram {
        val now = Instant.now()
        fun offset(i: Instant) = i.atOffset(ZoneOffset.ofHours(3)).toString()
        return ChannelProgram(
            id = title,
            title = title,
            subtitle = "S4 E15",
            start = offset(now.minusSeconds(600)),
            end = end ?: offset(now.plusSeconds(1200)),
        )
    }

    private val numbered = listOf(1, 7, 99, 100, 512, 999, 1000, 1400, 1401, 2048, 4321, 9999).mapIndexed { i, n ->
        Channel(id = i + 1L, number = n, name = "Звёздные Войны $n", favorite = n == 1400, now = onAirNow("Войны Клонов $n"))
    }

    private fun show(guide: MiniGuide, currentId: Long, selected: Int) {
        compose.setContent {
            AntresolTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    ChannelListPanel(guide, filterTitle(ChannelFilter.All), currentId = currentId, selected = selected)
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    @Test
    fun fourDigitNumbersFitOnOneLine() {
        show(MiniGuide(numbered, 7), currentId = 8, selected = 7)
        shot("mini-guide-4-digits")
        listOf("1000", "1400", "1401", "2048").forEach { number ->
            val layout = layoutOf(number)
            assertEquals("$number wraps", 1, layout.lineCount)
            assertFalse("$number is cut: ${layout.size} for ${layout.multiParagraph.width}", layout.hasVisualOverflow)
        }
    }

    @Test
    fun moreBelowIsShownUntilTheEnd() {
        show(MiniGuide(numbered, 0), currentId = 1, selected = 0)
        shot("mini-guide-more-below")
        compose.onAllNodesWithTag("mini-guide-more-below", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun noMoreBelowHintAtTheEnd() {
        show(MiniGuide(numbered, numbered.lastIndex), currentId = numbered.last().id, selected = numbered.lastIndex)
        shot("mini-guide-end")
        compose.onAllNodesWithTag("mini-guide-more-below", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theEndTimeIsShownForAnOffsetTimeAndNoBareUntilWithout() {
        val channels = listOf(
            Channel(id = 1, number = 21, name = "Звёздные Войны", now = onAirNow("Войны Клонов")),
            Channel(id = 2, number = 22, name = "Без времени", now = onAirNow("Без конца", end = "soon")),
        )
        show(MiniGuide(channels, 0), currentId = 1, selected = 0)
        shot("mini-guide-until")
        val until = compose.onAllNodesWithText("until ", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one «until», for the readable end only", 1, until.size)
        val text = until.single().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        assertTrue("«$text» has no time", Regex("""until \d{1,2}:\d{2}""").containsMatchIn(text))
    }

    private fun shot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
