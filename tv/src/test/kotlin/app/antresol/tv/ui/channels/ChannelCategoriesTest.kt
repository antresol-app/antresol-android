package app.antresol.tv.ui.channels

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.model.ChannelCategory
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelList
import app.antresol.tv.AntresolTvTheme
import app.antresol.ui.channels.ChannelsApi
import app.antresol.ui.channels.ChannelsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * A big playlist has dozens of categories: they are one button and a list on the left, not a row
 * of pills, and Left from the channel list opens the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
class ChannelCategoriesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // 40 channels: 19 groups of 2 (a playlist's blocks), the last two without a group; channel 3 is a favourite.
    private var channels = (1..40).map { n ->
        Channel(id = n.toLong(), number = n, name = "Channel $n", category = if (n > 38) "" else "Group ${(n - 1) / 2 + 1}", favorite = n == 3)
    }
    private var serverCategories: List<ChannelCategory>? = null
    private val api = object : ChannelsApi {
        override suspend fun channels() = ChannelList(channels, lastChannel = 1, categories = serverCategories)
        override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit
        override suspend fun epg(fromIso: String, hours: Int) = ChannelEpg()
    }

    private fun show(): ChannelsViewModel {
        val viewModel = ChannelsViewModel(api)
        compose.setContent {
            AntresolTvTheme {
                ChannelsScreen(viewModel, onWatch = { _, _ -> }, onScrolledToTop = {}, canTakeFocus = { true })
            }
        }
        waitFor { viewModel.channels != null && compose.onAllNodesWithText("Channel 1").fetchSemanticsNodes().isNotEmpty() }
        return viewModel
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000) {
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        condition()
    }

    private fun key(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun panelShown() = compose.onAllNodesWithText("Categories").fetchSemanticsNodes().isNotEmpty()

    private fun button() = compose.onNode(hasContentDescription("Channels to show", substring = true), useUnmergedTree = true)

    @Test
    fun oneButtonNamesTheCategoryAndTheListHasEveryCategoryWithItsCount() {
        val viewModel = show()
        assertEquals(ChannelFilter.All, viewModel.filter)
        button().assertContentDescriptionContains("All Channels, 40")
        assertTrue("categories are not pills in the header", compose.onAllNodesWithText("Group 7").fetchSemanticsNodes().isEmpty())

        button().press()
        waitFor(::panelShown)
        // The list scrolls; the first entries are there, each with its count after the name.
        fun entry(name: String) = compose.onNode(hasText(name)).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf("Favourites", "1"), entry("Favourites"))
        assertEquals(listOf("Group 1", "2"), entry("Group 1"))
        // 19 groups + «no category» + All + Favourites.
        assertEquals(22, viewModel.filterOptions.size)
    }

    @Test
    fun choosingACategoryNarrowsTheListAndHandsFocusToIt() {
        val viewModel = show()
        button().press()
        waitFor(::panelShown)
        compose.onNode(hasText("Group 2")).press()
        waitFor { !panelShown() }
        assertEquals(ChannelFilter.Category("group:Group 2"), viewModel.filter)
        assertEquals(listOf(3L, 4L), viewModel.shown.map { it.id })
        button().assertContentDescriptionContains("Group 2, 2")
        waitFor { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a channel of the category has focus", compose.onNode(isFocused()).fetchSemanticsNode().config.toString().contains("Channel"))
    }

    @Test
    fun leftFromTheChannelListOpensTheCategoriesAndBackClosesThem() {
        show()
        waitFor { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor(::panelShown)
        key(KeyEvent.KEYCODE_BACK)
        waitFor { !panelShown() }
    }

    @Test
    fun theServersTopicsComeFirstUnderHeadingsAndNameTheButton() {
        // AI topics first, then a source, then the playlist's groups, as the server orders them.
        serverCategories = listOf(
            ChannelCategory("ai:news", "Новости", ChannelCategory.KIND_AI, 3),
            ChannelCategory("ai:sport", "Спорт", ChannelCategory.KIND_AI, 2),
            ChannelCategory("source:7", "Playlist A", ChannelCategory.KIND_SOURCE, 40),
            ChannelCategory("group:Group 1", "Group 1", ChannelCategory.KIND_GROUP, 2),
        )
        channels = channels.map { c ->
            c.copy(categories = buildList {
                if (c.id in 1..3) add("ai:news")
                if (c.id in 3..4) add("ai:sport")
                add("source:7")
                if (c.id <= 2) add("group:Group 1")
            })
        }
        val viewModel = show()
        button().assertContentDescriptionContains("All Channels, 40")
        button().press()
        waitFor(::panelShown)
        // Headings (only with more than one kind), the first entries are Favourites and All without one (as the web).
        compose.onNodeWithText("TOPICS").assertExists()
        compose.onNodeWithText("Новости").assertExists()
        compose.onNodeWithText("Favourites").assertExists()
        assertEquals(
            listOf("Favorites", "All", "ai:news", "ai:sport", "source:7", "group:Group 1"),
            viewModel.filterOptions.map { (it.filter as? ChannelFilter.Category)?.id ?: it.filter.key.replaceFirstChar(Char::titlecase) },
        )
        compose.onNode(hasText("Новости")).press()
        waitFor { !panelShown() }
        assertEquals(listOf(1L, 2L, 3L), viewModel.shown.map { it.id })
        button().assertContentDescriptionContains("Новости, 3")
        // The «Sources» and «Groups» headings are below (the list scrolls).
        button().press()
        waitFor(::panelShown)
        compose.onNodeWithText("SOURCES").assertExists()
    }

    @Test
    fun channelsWithoutAGroupAreTheLastEntry() {
        val viewModel = show()
        button().press()
        waitFor(::panelShown)
        viewModel.select(ChannelFilter.Category(Channels.NO_CATEGORY))
        assertEquals(listOf(39L, 40L), viewModel.shown.map { it.id })
    }
}

/** OK on a TV surface (touch is not how it is pressed). */
private fun SemanticsNodeInteraction.press() = performSemanticsAction(SemanticsActions.OnClick)

private fun SemanticsNodeInteraction.assertContentDescriptionContains(text: String) {
    val description = fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString(", ").orEmpty()
    assertTrue("«$description» has «$text»", description.contains(text))
}
