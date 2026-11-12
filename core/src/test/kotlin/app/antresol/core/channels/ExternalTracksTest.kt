package app.antresol.core.channels

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.ChannelList
import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.PlaybackPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTracksTest {
    private fun name(index: Int, lang: String?) = lang ?: "Track ${index + 1}"

    @Test
    fun optionsUseTheStreamsOwnLabelsAndStayUnique() {
        val options = ExternalTracks.options(
            listOf(
                StreamTrack(0, "English", "en", mimeType = "text/vtt"),
                StreamTrack(1, "English", "en", mimeType = "application/cea-608"),
                StreamTrack(2, "Français", "fr"),
                StreamTrack(3, null, "de"),
                StreamTrack(4, null, null),
            ),
            ::name,
        )
        assertEquals(listOf("English", "Français", "de", "Track 5", "English (CC)"), options.map { it.label })
        assertEquals(listOf("g0", "g2", "g3", "g4", "g1"), options.map { it.slot })
        assertEquals(1, ExternalTracks.group(options.last()))
        val audio = ExternalTracks.options(listOf(StreamTrack(0, "English", "en", 2), StreamTrack(1, "English", "en", 6), StreamTrack(2, "English", "en", 2)), ::name)
        assertEquals(listOf("English", "English 5.1", "English 2"), audio.map { it.name })
    }

    @Test
    fun theDefaultIsTheProfilesLanguageAndTheChoiceIsRememberedPerChannel() {
        val program = ChannelProgram(
            audio = ExternalTracks.options(listOf(StreamTrack(0, "Original", "en"), StreamTrack(1, "Русский", "ru")), ::name),
            subtitles = ExternalTracks.options(listOf(StreamTrack(0, "English", "en"), StreamTrack(1, "Français", "fr")), ::name),
        )
        val prefs = PlaybackPrefs(audioLangs = listOf("ru"), subMode = PlaybackPrefs.SUB_OFF)
        val memory = InMemoryExternalTrackMemory()
        val default = LiveTracks.select(program, prefs, memory.get(7) ?: LiveTrackChoice())
        assertEquals("Русский", default.audio?.name)
        assertNull(default.subtitles)

        // The viewer picks French subtitles on channel 7; the stream later lists its groups in another order.
        memory.put(7, LiveTrackChoice(subtitles = SubtitleChoice.On(program.subtitles!![1])).forMemory())
        val reordered = ChannelProgram(
            audio = program.audio,
            subtitles = ExternalTracks.options(listOf(StreamTrack(0, "Français", "fr"), StreamTrack(1, "English", "en")), ::name),
        )
        val again = LiveTracks.select(reordered, prefs, memory.get(7)!!)
        assertEquals("Français", again.subtitles?.name)
        assertEquals("g0", again.subtitles?.slot)
        // Another channel keeps the profile's default.
        assertNull(memory.get(8))
    }

    @Test
    fun externalChannelsDecode() {
        val list = AntresolClient.json.decodeFromString<ChannelList>(
            """{"channels":[
              {"id":5,"number":1,"name":"Own","now":{"id":"5-1","itemId":18,"kind":"movie","title":"A","start":"2026-11-12T10:00:00Z","end":"2026-11-12T12:00:00Z"}},
              {"id":900,"number":100,"name":"Demo","category":"Демо","external":true,"canEdit":false,"logoUrl":"http://logo.example.test/a.png",
               "stream":{"url":"http://a.example.test/live.m3u8","userAgent":"UA","referrer":"http://r.example.test/",
                         "fallbacks":[{"url":"http://b.example.test/live.m3u8","unsupported":[]}],"unsupported":["web"]},
               "now":{"id":"x3-1762941600000","itemId":0,"kind":"external","title":"News","overview":"About","genres":["Новости"],
                      "poster":"http://icon.example.test/p.png","start":"2026-11-12T10:00:00Z","end":"2026-11-12T11:00:00Z"}}],
              "lastChannel":900}""",
        )
        val own = list.channels[0]
        val ext = list.channels[1]
        assertFalse(own.external)
        assertNull(own.stream)
        assertTrue(own.now!!.isLibraryItem)
        assertTrue(ext.external)
        assertEquals(listOf("http://a.example.test/live.m3u8", "http://b.example.test/live.m3u8"), ext.stream!!.sources.map { it.url })
        assertEquals("UA", ext.stream!!.sources[0].userAgent)
        assertEquals(listOf("web"), ext.stream!!.unsupported)
        assertEquals("external", ext.now!!.kind)
        assertFalse(ext.now!!.isLibraryItem)
        assertEquals(900L, list.lastChannel)

        val epg = AntresolClient.json.decodeFromString<ChannelEpg>(
            """{"from":"a","to":"b","channels":[{"id":900,"number":100,"name":"Demo","external":true,"programs":[{"id":"x3-1","itemId":0,"kind":"external","title":"News"}]}]}""",
        )
        assertTrue(epg.channels.single().external)
        assertFalse(epg.channels.single().programs.single().isLibraryItem)
    }
}
