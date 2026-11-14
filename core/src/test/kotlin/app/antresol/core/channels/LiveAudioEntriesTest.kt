package app.antresol.core.channels

import app.antresol.core.model.ProgramTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAudioEntriesTest {
    private val lost = ProgramTrack(name = "LostFilm", label = "Русский — LostFilm", lang = "ru")
    private val clean = ProgramTrack(name = "LostFilm (очищено)", label = "Русский — LostFilm (очищено)", lang = "ru", cleanedOf = "LostFilm")
    private val orig = ProgramTrack(name = "Оригинал (English)", label = "Оригинал (English)", lang = "en")
    private val audio = listOf(lost, clean, orig)

    @Test
    fun theCleanedVoiceIsASwitch() {
        val off = liveAudioEntries(audio, "LostFilm")
        assertEquals(listOf("LostFilm", "Оригинал (English)", "LostFilm (очищено)"), off.map { it.track.name })
        assertEquals(listOf(true, false, false), off.map { it.checked })
        assertEquals(clean, off.last().plays)
        val on = liveAudioEntries(audio, "LostFilm (очищено)")
        assertEquals(listOf(true, false, true), on.map { it.checked })
        assertEquals(lost, on.last().plays)
        assertEquals(1, liveAudioEntries(audio, "Оригинал (English)").count { it.checked })
        assertEquals(2, liveAudioEntries(audio, "Оригинал (English)").size)
    }
}
