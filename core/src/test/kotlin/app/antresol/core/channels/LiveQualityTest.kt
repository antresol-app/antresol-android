package app.antresol.core.channels

import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.Quality
import app.antresol.core.model.QualityInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveQualityTest {
    @Test
    fun ownChannelOffersTheFilmChoicesWithTheAppliedQualityOnAuto() {
        val away = LiveQuality.own(Quality.AUTO, QualityInfo("auto", "720", remote = true))
        assertEquals(Quality.ALL, away.map { it.value })
        assertEquals(LiveQualityOption(Quality.AUTO, selected = true, applied = "720"), away.first())
        assertTrue(away.drop(1).none { it.selected || it.applied != null })

        // At home auto is the original: no detail, as the web player
        assertEquals(null, LiveQuality.own(Quality.AUTO, QualityInfo("auto", "original")).first().applied)
        // Unknown yet
        assertEquals(null, LiveQuality.own(Quality.AUTO, null).first().applied)
        // A fixed choice: ticked, no detail on «Авто»
        val fixed = LiveQuality.own(Quality.P480, QualityInfo("480", "480", remote = true))
        assertEquals(listOf(Quality.P480), fixed.filter { it.selected }.map { it.value })
        assertEquals(null, fixed.first().applied)
        // An unknown stored value falls back to auto
        assertEquals(Quality.AUTO, LiveQuality.own("4k", null).single { it.selected }.value)
    }

    @Test
    fun externalStreamOffersItsOwnHeightsOnlyWithSeveralVariants() {
        assertEquals(emptyList<LiveQualityOption>(), LiveQuality.external(emptyList(), null))
        assertEquals(emptyList<LiveQualityOption>(), LiveQuality.external(listOf(720), null))
        // Same height twice (two bitrates) and an unknown height are one variant
        assertEquals(emptyList<LiveQualityOption>(), LiveQuality.external(listOf(720, 720, -1), null))

        val auto = LiveQuality.external(listOf(480, 1080, 720, 720), null)
        assertEquals(listOf("auto", "1080", "720", "480"), auto.map { it.value })
        assertEquals(listOf("auto"), auto.filter { it.selected }.map { it.value })

        val limited = LiveQuality.external(listOf(480, 1080, 720), 720)
        assertEquals(listOf("720"), limited.filter { it.selected }.map { it.value })
        // A limit that matches no variant (the next channel's stream): «Авто»
        assertEquals(listOf("auto"), LiveQuality.external(listOf(480, 1080), 720).filter { it.selected }.map { it.value })
    }

    @Test
    fun externalChoiceIsAHeightLimitAndAutoClearsIt() {
        assertEquals(720, LiveQuality.externalMaxHeight("720"))
        assertEquals(null, LiveQuality.externalMaxHeight(Quality.AUTO))
        assertEquals(null, LiveQuality.externalMaxHeight(Quality.ORIGINAL))
    }

    private class QualityApi : LiveApi {
        val opens = mutableListOf<Pair<Long, String>>()
        var channels = listOf(
            Channel(id = 1, number = 1, name = "Ours", category = "a"),
            Channel(id = 2, number = 2, name = "Iptv", category = "a", external = true),
        )

        override suspend fun channels(): List<Channel> = channels
        override suspend fun guide(channelId: Long): List<ChannelProgram> = emptyList()
        override suspend fun open(channelId: Long): String = error("the quality overload is used")
        override suspend fun open(channelId: Long, quality: String): String {
            opens += channelId to quality
            return "live/$channelId?q=$quality"
        }
        override suspend fun setLast(channelId: Long) = Unit
        override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit
        override suspend fun prefs() = PlaybackPrefs()
    }

    @Test
    fun sessionOpensInTheSavedQualityAndReopensOnAChange() = runTest {
        val api = QualityApi()
        val session = LiveTvSession(api, backgroundScope, 1, quality = Quality.P720)
        session.start()
        runCurrent()
        assertEquals(listOf(1L to "720"), api.opens)
        val first = session.state.value.stream as LiveStream.Ready

        session.setQuality(Quality.AUTO)
        runCurrent()
        assertEquals(listOf(1L to "720", 1L to "auto"), api.opens)
        val second = session.state.value.stream as LiveStream.Ready
        assertTrue(second.generation > first.generation)
        assertEquals("live/1?q=auto", second.url)

        // Same value: nothing reopens
        session.setQuality(Quality.AUTO)
        runCurrent()
        assertEquals(2, api.opens.size)
        session.stop()
    }

    @Test
    fun anExternalChannelIsNotReopenedOnAQualityChange() = runTest {
        val api = QualityApi()
        val session = LiveTvSession(api, backgroundScope, 2)
        session.start()
        runCurrent()
        val before = session.state.value.stream
        session.setQuality(Quality.P480)
        runCurrent()
        assertEquals(before, session.state.value.stream)
        assertTrue(api.opens.isEmpty())
        assertEquals(Quality.P480, session.quality)
        session.stop()
    }
}
