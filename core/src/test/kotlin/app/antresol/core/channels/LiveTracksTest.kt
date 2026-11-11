package app.antresol.core.channels

import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.ProgramTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTracksTest {
    private val ru = ProgramTrack("ru", "Русский", "Русский — LostFilm", "ru")
    private val ru2 = ProgramTrack("ru2", "Русский 2", "Русский — Кубик в кубе", "ru")
    private val en = ProgramTrack("en", "English", "English", "en")
    private val orig = ProgramTrack("orig", "Оригинал", "Оригинал (fr)", "fr")
    private val subRu = ProgramTrack("ru", "Русские", "Русские", "ru")
    private val subEn = ProgramTrack("en", "English", "English", "en")

    private val film = ChannelProgram(id = "1-1", audio = listOf(ru, ru2, en, orig), subtitles = listOf(subRu, subEn))
    private val russianOnly = ChannelProgram(id = "1-2", audio = listOf(ru), subtitles = listOf(subRu))

    @Test
    fun audioIsTheFirstPreferredLanguageThatThePorgrammeHas() {
        assertEquals(en, LiveTracks.defaultAudio(film, PlaybackPrefs(audioLangs = listOf("eng", "ru"))))
        assertEquals(ru, LiveTracks.defaultAudio(russianOnly, PlaybackPrefs(audioLangs = listOf("en"))))
        assertEquals(ru, LiveTracks.defaultAudio(film, null))
        assertNull(LiveTracks.defaultAudio(ChannelProgram(), null))
    }

    @Test
    fun subtitlesFollowTheMode() {
        val always = PlaybackPrefs(audioLangs = listOf("ru"), subMode = "always", subLangs = listOf("en", "ru"))
        assertEquals(subEn, LiveTracks.defaultSubtitles(film, always, ru))
        assertNull(LiveTracks.defaultSubtitles(film, always.copy(subMode = "off"), ru))
        val foreign = always.copy(subMode = "foreign", subLangs = listOf("ru"))
        assertNull(LiveTracks.defaultSubtitles(film, foreign, ru))
        assertEquals(subRu, LiveTracks.defaultSubtitles(film, foreign, orig))
        assertNull(LiveTracks.defaultSubtitles(ChannelProgram(), always, ru))
    }

    @Test
    fun theViewersChoiceSticksAcrossProgrammes() {
        val prefs = PlaybackPrefs(audioLangs = listOf("ru"), subMode = "always", subLangs = listOf("ru"))
        val choice = LiveTrackChoice(audio = en, subtitles = SubtitleChoice.Off)
        assertEquals(LiveTrackSelection(en, null), LiveTracks.select(film, prefs, choice))
        // A programme without the slot still selects it: the server plays its fallback there.
        assertEquals(LiveTrackSelection(en, null), LiveTracks.select(russianOnly, prefs, choice))
        assertEquals(LiveTrackSelection(ru, subRu), LiveTracks.select(russianOnly, prefs, LiveTrackChoice()))
        // A picked slot takes the programme's own label.
        val picked = LiveTracks.select(film, prefs, LiveTrackChoice(audio = ru.copy(label = "old"), subtitles = SubtitleChoice.On(subEn)))
        assertEquals(LiveTrackSelection(ru, subEn), picked)
    }
}
