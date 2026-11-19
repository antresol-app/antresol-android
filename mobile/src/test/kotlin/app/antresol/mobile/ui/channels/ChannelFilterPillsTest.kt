package app.antresol.mobile.ui.channels

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelCategory
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelList
import app.antresol.ui.channels.ChannelsApi
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** The phone's filter pills: «Все · Избранное», then the server's categories in its order (AI topics first). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xxhdpi")
class ChannelFilterPillsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var categories: List<ChannelCategory>? = null
    private var channels = listOf(
        Channel(1, 1, "One", category = "RU | Sport", categories = listOf("ai:sport", "source:3", "group:RU | Sport")),
        Channel(2, 2, "Two", category = "RU | News", categories = listOf("ai:news", "source:3", "group:RU | News")),
        Channel(3, 3, "Three", category = "RU | News", categories = listOf("ai:news", "group:RU | News")),
    )
    private val api = object : ChannelsApi {
        override suspend fun channels() = ChannelList(channels, categories = categories)
        override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit
        override suspend fun epg(fromIso: String, hours: Int) = ChannelEpg()
    }

    private fun show(): ChannelsViewModel {
        val viewModel = ChannelsViewModel(api)
        compose.setContent { AntresolTheme { ChannelsScreen(viewModel, onWatch = { _, _ -> }) } }
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            viewModel.channels != null && compose.onAllNodesWithText("Three").fetchSemanticsNodes().isNotEmpty()
        }
        return viewModel
    }

    /** The texts under the filter row, in order (each pill is one). */
    private fun pills(): List<String> {
        fun texts(node: SemanticsNode): List<String> =
            listOfNotNull(node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString("")).ifEmpty { node.children.flatMap(::texts) }
        return texts(compose.onNodeWithTag("tv-filters", useUnmergedTree = true).fetchSemanticsNode())
    }

    @Test
    fun theServersCategoriesAreThePillsAndNarrowTheList() {
        categories = listOf(
            ChannelCategory("ai:news", "Новости", ChannelCategory.KIND_AI, 2),
            ChannelCategory("ai:sport", "Спорт", ChannelCategory.KIND_AI, 1),
            ChannelCategory("source:3", "Playlist A", ChannelCategory.KIND_SOURCE, 2),
            ChannelCategory("group:RU | News", "RU | News", ChannelCategory.KIND_GROUP, 2),
            ChannelCategory("group:RU | Sport", "RU | Sport", ChannelCategory.KIND_GROUP, 1),
        )
        val viewModel = show()
        assertEquals(listOf("Favourites", "All", "Новости", "Спорт", "Playlist A", "RU | News", "RU | Sport"), pills())
        compose.onNodeWithText("Новости").performClick()
        compose.waitForIdle()
        assertEquals(ChannelFilter.Category("ai:news"), viewModel.filter)
        assertEquals(listOf(2L, 3L), viewModel.shown.map { it.id })
    }

    @Test
    fun anOlderServerKeepsThePillsOfTheChannelsOwnCategories() {
        // It sends neither the list nor the channels' ids, only each channel's `category` text.
        channels = channels.map { it.copy(categories = null) }
        val viewModel = show()
        assertEquals(listOf("Favourites", "All", "RU | Sport", "RU | News"), pills())
        assertEquals(ChannelFilter.All, viewModel.filter)
    }
}
