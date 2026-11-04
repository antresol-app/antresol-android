package app.antresol.core.playback

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.SeasonInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EpisodesTest {
    private fun episode(id: Long, no: Int) = EpisodeCard(id = id, episodeNo = no)

    private val series = ItemDetail(
        id = 1,
        seasons = listOf(
            SeasonInfo(id = 20, number = 2, episodes = listOf(episode(21, 1))),
            SeasonInfo(id = 10, number = 1, episodes = listOf(episode(12, 2), episode(11, 1))),
        ),
    )

    @Test
    fun walksSeasonsInOrder() {
        assertEquals(listOf(11L, 12L, 21L), Episodes.ordered(series).map { it.id })
        assertEquals(12L, Episodes.after(series, 11)?.id)
        assertEquals(21L, Episodes.after(series, 12)?.id)
        assertNull(Episodes.after(series, 21))
        assertNull(Episodes.after(series, 99))
    }

    @Test
    fun playStartsWithNextOrFirst() {
        assertEquals(11L, Episodes.toPlay(series)?.id)
        assertEquals(21L, Episodes.toPlay(series.copy(next = episode(21, 1)))?.id)
    }

    @Test
    fun realSeriesSampleHasAnOrder() {
        val url = requireNotNull(javaClass.classLoader.getResource("api-samples/item-series.json"))
        val sample = AntresolClient.json.decodeFromString(ItemDetail.serializer(), File(url.toURI()).readText())
        val ordered = Episodes.ordered(sample)
        assertEquals(ordered.getOrNull(1)?.id, Episodes.after(sample, ordered.first().id)?.id)
    }
}
