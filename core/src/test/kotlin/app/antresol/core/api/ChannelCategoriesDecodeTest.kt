package app.antresol.core.api

import app.antresol.core.model.ChannelCategory
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `GET /api/channels` with the server's categories (and the stream format), and the answer of an older server. */
class ChannelCategoriesDecodeTest {
    private val json = AntresolClient.json

    @Test
    fun theServersCategoriesAndTheStreamFormatAreRead() {
        val list = json.decodeFromString<ChannelList>(
            """
            {"channels": [
              {"id": 1, "number": 1, "name": "Own", "category": "Movies by Genre", "categories": ["group:Movies by Genre"]},
              {"id": 2, "number": 2, "name": "News 24", "category": "RU | Новости", "external": true,
               "categories": ["ai:news", "source:3", "group:RU | Новости"],
               "stream": {"url": "https://example.invalid/a", "format": "ts",
                          "fallbacks": [{"url": "https://example.invalid/b", "format": "hls"}, {"url": "https://example.invalid/c"}]}},
              {"id": 3, "number": 3, "name": "Nowhere"}
            ],
             "lastChannel": 2, "canManage": false, "canCreate": true,
             "categories": [
               {"id": "ai:news", "name": "Новости", "kind": "ai", "count": 1},
               {"id": "source:3", "name": "Playlist A", "kind": "source", "count": 1},
               {"id": "group:RU | Новости", "name": "RU | Новости", "kind": "group", "count": 1}
             ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ChannelCategory("ai:news", "Новости", ChannelCategory.KIND_AI, 1),
                ChannelCategory("source:3", "Playlist A", ChannelCategory.KIND_SOURCE, 1),
                ChannelCategory("group:RU | Новости", "RU | Новости", ChannelCategory.KIND_GROUP, 1),
            ),
            list.categories,
        )
        assertEquals(listOf("group:Movies by Genre"), list.channels[0].categories)
        assertEquals(listOf("ai:news", "source:3", "group:RU | Новости"), list.channels[1].categories)
        // The server leaves the field out of a channel that is in no category.
        assertNull(list.channels[2].categories)
        val stream = list.channels[1].stream!!
        assertEquals(listOf("ts", "hls", null), stream.sources.map { it.format })
    }

    @Test
    fun anOlderServerSendsNone() {
        val list = json.decodeFromString<ChannelList>("""{"channels": [{"id": 1, "number": 1, "name": "A", "category": "Films"}], "canManage": false, "canCreate": false}""")
        assertNull(list.categories)
        assertNull(list.channels.single().categories)
        assertEquals("Films", list.channels.single().category)
    }

    @Test
    fun theGuideRowsCarryTheSameIds() {
        val epg = json.decodeFromString<ChannelEpg>(
            """{"from": "2026-11-18T10:00:00Z", "to": "2026-11-18T16:00:00Z", "channels": [{"id": 2, "number": 2, "name": "News 24", "external": true, "categories": ["ai:news"], "programs": []}, {"id": 1, "number": 1, "name": "A", "programs": []}]}""",
        )
        assertEquals(listOf("ai:news"), epg.channels[0].categories)
        assertNull(epg.channels[1].categories)
    }
}
