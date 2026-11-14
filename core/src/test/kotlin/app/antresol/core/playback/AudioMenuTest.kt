package app.antresol.core.playback

import app.antresol.core.model.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMenuTest {
    @Test
    fun cleanedTracksSitUnderTheirSource() {
        val audio = listOf(
            AudioTrack(1, lang = "rus", title = "LostFilm"),
            AudioTrack(2, lang = "eng"),
            AudioTrack(2000, lang = "ru", title = "Кураж-Бамбей", external = true, src = "a"),
            AudioTrack(2001, lang = "ru", title = "LostFilm", external = true, src = "b", kind = "cleaned", cleanedFrom = 1),
            AudioTrack(2002, lang = "ru", external = true, src = "c", kind = "cleaned", cleanedFrom = 9),
        )
        assertEquals(listOf(1, 2001, 2, 2000, 2002), AudioMenu.order(audio).map { it.index })
    }

    private val voices = listOf(
        AudioTrack(1, lang = "eng"),
        AudioTrack(2, lang = "rus", title = "LostFilm"),
        AudioTrack(2000, lang = "ru", title = "LostFilm", external = true, src = "c", kind = "cleaned", cleanedFrom = 2),
    )

    @Test
    fun cleanedIsASwitchOnItsVoice() {
        val off = AudioMenu.cleanedSwitch(voices, 2)
        assertEquals(listOf(1, 2), off.rows.map { it.track.index })
        assertEquals(listOf(false, true), off.rows.map { it.checked })
        assertEquals(false, off.toggle?.on)
        assertEquals(2000, off.toggle?.target?.index)

        val on = AudioMenu.cleanedSwitch(voices, 2000)
        assertEquals(listOf(false, true), on.rows.map { it.checked })
        assertEquals(listOf(1, 2000), on.rows.map { it.plays.index })
        assertEquals(true, on.toggle?.on)
        assertEquals(2, on.toggle?.target?.index)

        assertEquals(null, AudioMenu.cleanedSwitch(voices, 1).toggle)
    }
}
