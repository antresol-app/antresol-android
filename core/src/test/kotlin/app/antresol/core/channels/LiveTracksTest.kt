package app.antresol.core.channels

import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.ProgramTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTracksTest {
    // Round 5: names are whatever the server's track set calls them, in no fixed order or count.
    private fun audio(n: Int, name: String, lang: String, label: String = name) = ProgramTrack("audio-$n", name, label, lang)
    private fun subs(n: Int, name: String, lang: String) = ProgramTrack("subs-$n", name, name, lang)

    private val dub = audio(0, "Дубляж", "ru", "Русский — Дубляж")
    private val lostfilm = audio(1, "LostFilm", "ru", "Русский — LostFilm")
    private val kubik = audio(2, "Кубик в Кубе", "ru")
    private val kurazh = audio(3, "Кураж-Бамбей", "ru")
    private val cleaned = audio(4, "LostFilm (очищено)", "ru")
    private val original = audio(5, "Оригинал (English)", "en")
    private val french = audio(6, "Français", "fr")
    private val commentary = audio(7, "English 2", "en")
    private val subRu = subs(0, "Русские", "ru")
    private val subRuForced = subs(1, "Русские (надписи)", "ru")
    private val subEn = subs(2, "English", "en")
    private val subFr = subs(3, "Français", "fr")
    private val subDe = subs(4, "Deutsch", "de")
    private val subUk = subs(5, "Українська", "uk")

    private val eight = listOf(dub, lostfilm, kubik, kurazh, cleaned, original, french, commentary)
    private val six = listOf(subRu, subRuForced, subEn, subFr, subDe, subUk)
    private val big = ChannelProgram(id = "1-1", audio = eight, subtitles = six)
    private val dubOnly = ChannelProgram(id = "1-2", audio = listOf(dub), subtitles = listOf(subRu))
    private val englishFilm = ChannelProgram(id = "1-3", audio = listOf(original.copy(name = "Оригинал (English)")), subtitles = emptyList())
    private val frenchFilm = ChannelProgram(id = "1-4", audio = listOf(audio(0, "Оригинал (Français)", "fr")), subtitles = listOf(subFr))

    @Test
    fun audioIsTheFirstPreferredLanguageThatTheProgrammeHas() {
        assertEquals(original, LiveTracks.defaultAudio(big, PlaybackPrefs(audioLangs = listOf("eng", "ru"))))
        assertEquals(dub, LiveTracks.defaultAudio(big, PlaybackPrefs(audioLangs = listOf("ru"))))
        assertEquals(french, LiveTracks.defaultAudio(big, PlaybackPrefs(audioLangs = listOf("fr-FR"))))
        assertEquals(dub, LiveTracks.defaultAudio(dubOnly, PlaybackPrefs(audioLangs = listOf("en"))))
        assertEquals(dub, LiveTracks.defaultAudio(big, null))
        // Russian first even when it is not listed first.
        assertEquals(kubik, LiveTracks.defaultAudio(ChannelProgram(audio = listOf(french, kubik)), PlaybackPrefs(audioLangs = listOf("de"))))
        assertEquals(french, LiveTracks.defaultAudio(ChannelProgram(audio = listOf(french, commentary)), null))
        assertNull(LiveTracks.defaultAudio(ChannelProgram(), null))
    }

    @Test
    fun subtitlesFollowTheMode() {
        val always = PlaybackPrefs(audioLangs = listOf("ru"), subMode = "always", subLangs = listOf("en", "ru"))
        assertEquals(subEn, LiveTracks.defaultSubtitles(big, always, dub))
        assertNull(LiveTracks.defaultSubtitles(big, always.copy(subMode = "off"), dub))
        val foreign = always.copy(subMode = "foreign", subLangs = listOf("ru"))
        assertNull(LiveTracks.defaultSubtitles(big, foreign, dub))
        assertEquals(subRu, LiveTracks.defaultSubtitles(big, foreign, original))
        assertNull(LiveTracks.defaultSubtitles(ChannelProgram(), always, dub))
    }

    @Test
    fun anyOfEightRenditionsCanBePickedByName() {
        val prefs = PlaybackPrefs(audioLangs = listOf("ru"))
        eight.forEach { track ->
            assertEquals(track, LiveTracks.select(big, prefs, LiveTrackChoice(audio = track)).audio)
        }
        six.forEach { track ->
            assertEquals(track, LiveTracks.select(big, prefs, LiveTrackChoice(subtitles = SubtitleChoice.On(track))).subtitles)
        }
    }

    @Test
    fun theChoiceSticksByNameThenByLanguage() {
        val prefs = PlaybackPrefs(audioLangs = listOf("ru"), subMode = "always", subLangs = listOf("ru"))
        // Same name in the next programme: the same rendition, with that programme's own label.
        val next = ChannelProgram(id = "2", audio = listOf(dub, kurazh.copy(slot = "audio-1", label = "Русский — Кураж-Бамбей")), subtitles = listOf(subRu))
        assertEquals(kurazh.copy(slot = "audio-1", label = "Русский — Кураж-Бамбей"), LiveTracks.select(next, prefs, LiveTrackChoice(audio = kurazh)).audio)
        // No «Кураж-Бамбей» here: another Russian voice rather than a jump to the default language order.
        assertEquals(dub, LiveTracks.select(dubOnly, prefs, LiveTrackChoice(audio = kurazh)).audio)
        // The original in English sticks to the next English film by language.
        val toEnglish = LiveTracks.select(englishFilm, prefs, LiveTrackChoice(audio = commentary, subtitles = SubtitleChoice.Off))
        assertEquals(LiveTrackSelection(englishFilm.audio!!.first(), null), toEnglish)
        // Nothing of that name or language: this programme's default; the choice itself stays.
        assertEquals(frenchFilm.audio!!.first(), LiveTracks.select(frenchFilm, prefs, LiveTrackChoice(audio = commentary)).audio)
        assertEquals(LiveTrackSelection(dub, subRu), LiveTracks.select(dubOnly, prefs, LiveTrackChoice()))
    }

    @Test
    fun pickedSubtitlesFallBackByLanguageElseOff() {
        val prefs = PlaybackPrefs(audioLangs = listOf("ru"), subMode = "always", subLangs = listOf("ru"))
        assertEquals(subRu, LiveTracks.select(dubOnly, prefs, LiveTrackChoice(subtitles = SubtitleChoice.On(subRuForced))).subtitles)
        // No English subtitles here: none, not Russian ones.
        assertNull(LiveTracks.select(dubOnly, prefs, LiveTrackChoice(subtitles = SubtitleChoice.On(subEn))).subtitles)
        assertNull(LiveTracks.select(big, prefs, LiveTrackChoice(subtitles = SubtitleChoice.Off)).subtitles)
    }

    @Test
    fun unknownTracksKeepThePick() {
        val unknown = ChannelProgram(id = "x")
        assertEquals(LiveTrackSelection(kurazh, subDe), LiveTracks.select(unknown, null, LiveTrackChoice(kurazh, SubtitleChoice.On(subDe))))
    }
}
