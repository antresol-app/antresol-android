package app.antresol.tv.ui.channels

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 5: a live programme can have 8 audio renditions (and «Off» plus 6 subtitles). The TV's
 * panel must keep every choice reachable on the 540 dp screen: the selected one is always shown.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
class KeyPanelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val voices = listOf(
        "Русский — Дубляж", "Русский — LostFilm", "Русский — Кубик в Кубе", "Русский — Кураж-Бамбей",
        "Русский — LostFilm (очищено)", "Оригинал (English)", "Français", "English 2", "Deutsch", "Українська",
    )

    @Test
    fun theSelectedChoiceIsAlwaysOnScreen() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            AntresolTheme { KeyPanel(title = "Audio", actions = voices.map { PanelAction(it) {} }, selected = selected) }
        }
        val metrics = compose.activity.resources.displayMetrics
        val order = voices.indices.toList() + voices.indices.reversed()
        order.forEach { index ->
            selected = index
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val b = compose.onNodeWithText(voices[index], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("${voices[index]} at $b is off the ${metrics.heightPixels}px screen", b.top >= 0f && b.bottom <= metrics.heightPixels + 0.5f)
        }
    }
}
