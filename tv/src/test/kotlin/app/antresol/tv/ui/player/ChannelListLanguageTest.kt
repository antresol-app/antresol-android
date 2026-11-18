package app.antresol.tv.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.MiniGuide
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import app.antresol.tv.AntresolTvApp
import app.antresol.tv.ui.channels.filterTitle
import app.antresol.ui.R
import app.antresol.ui.channels.programClock
import app.antresol.ui.language.ProfileLocaleKeeper
import app.antresol.ui.theme.AntresolTheme
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A Russian profile on a TV set to English: the player's channel list has its title and every
 * row's «до HH:mm» in the same language, and text resolved through the application context (view
 * models, the media session) is in it as well.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
class ChannelListLanguageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val savedLocale = Locale.getDefault()

    @After
    fun restore() {
        app.settings.profileLanguage = ""
        Locale.setDefault(savedLocale)
    }

    /** The activity as MainActivity is under a "ru" profile: its resources go through the keeper. */
    private class ProfileActivityContext(base: Context, language: String) : ContextWrapper(base) {
        private val keeper = ProfileLocaleKeeper().apply { this.language = language }

        override fun getResources(): Resources = keeper.resources(baseContext, super.getResources())
    }

    private fun onAirNow(): ChannelProgram {
        val now = Instant.now()
        fun at(i: Instant) = i.atOffset(ZoneOffset.ofHours(3)).toString()
        return ChannelProgram(id = "p", title = "Войны Клонов", subtitle = "S4 E15", start = at(now.minusSeconds(600)), end = at(now.plusSeconds(1200)))
    }

    @Test
    fun titleAndUntilAreInTheProfileLanguage() {
        assertEquals("the device is English", "All Channels", compose.activity.getString(R.string.tv_all_channels))
        val channels = listOf(1400, 1401, 1402).map { Channel(id = it.toLong(), number = it, name = "Канал $it", now = onAirNow()) }
        compose.setContent {
            val context = ProfileActivityContext(LocalContext.current, "ru")
            CompositionLocalProvider(LocalContext provides context) {
                AntresolTheme {
                    ChannelListPanel(MiniGuide(channels, 0), filterTitle(ChannelFilter.All), currentId = 1400, selected = 0)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Все каналы", useUnmergedTree = true).assertExists()
        val rows = compose.onAllNodesWithText("до ", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("every row has «до»", channels.size, rows.size)
        rows.forEach { node ->
            val text = node.config[SemanticsProperties.Text].joinToString()
            assertTrue("«$text» is not «до HH:mm»", Regex("""^до \d{2}:\d{2}$""").matches(text))
        }
        assertEquals(0, compose.onAllNodesWithText("until", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("All Channels", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theApplicationContextFollowsALanguageThatArrivedAfterStart() {
        // The process started with no profile language (English device); the server then said "ru".
        assertEquals("All Channels", app.getString(R.string.tv_all_channels))
        app.settings.profileLanguage = "ru"
        assertEquals("Все каналы", app.getString(R.string.tv_all_channels))
        assertEquals("до 21:40", app.getString(R.string.tv_until, "21:40"))
        val end = Instant.parse("2026-11-18T18:40:00Z").atOffset(ZoneOffset.ofHours(3)).toString()
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(programClock(app, end)))
        app.settings.profileLanguage = "en"
        assertEquals("All Channels", app.getString(R.string.tv_all_channels))
    }
}
