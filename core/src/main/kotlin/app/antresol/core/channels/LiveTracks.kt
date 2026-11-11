package app.antresol.core.channels

import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.ProgramTrack

/**
 * The viewer's own audio / subtitle choice in the live player, for this player session only: it
 * sticks across programmes and channel switches (the same slot), and is never sent to the server
 * (TV is TV: no track memory). Null fields mean «the profile's default».
 */
data class LiveTrackChoice(
    /** The audio slot picked (`ru`, `ru2`, `en`, `orig`) and its rendition NAME. */
    val audio: ProgramTrack? = null,
    /** The subtitles picked: a track, [SubtitlesOff], or null for the default. */
    val subtitles: SubtitleChoice? = null,
)

sealed interface SubtitleChoice {
    data object Off : SubtitleChoice

    data class On(val track: ProgramTrack) : SubtitleChoice
}

/** What the player selects for a programme: the HLS rendition NAMEs ([subtitles] null = off). */
data class LiveTrackSelection(val audio: ProgramTrack?, val subtitles: ProgramTrack?)

/**
 * Picks the live stream's renditions for a programme (spec «Audio and subtitles»): audio = the first
 * of the profile's `audioLangs` that the programme has a slot for, else `ru`; subtitles by `subMode`
 * (`off`; `always`: the first of `subLangs` present; `foreign`: when the audio's language is not one
 * of `audioLangs`). The viewer's [LiveTrackChoice] wins and keeps its slot across programmes.
 */
object LiveTracks {
    /** `ru`/`rus`/`ru-RU` → `ru`; the slots and the prefs use two-letter codes. */
    fun lang(code: String): String {
        val base = code.lowercase().substringBefore('-').substringBefore('_')
        return THREE_LETTER[base] ?: base
    }

    fun defaultAudio(program: ChannelProgram?, prefs: PlaybackPrefs?): ProgramTrack? {
        val audio = program?.audio.orEmpty()
        if (audio.isEmpty()) return null
        prefs?.audioLangs.orEmpty().map(::lang).forEach { wanted ->
            audio.firstOrNull { it.slot == wanted }?.let { return it }
        }
        return audio.firstOrNull { it.slot == SLOT_RU } ?: audio.first()
    }

    fun defaultSubtitles(program: ChannelProgram?, prefs: PlaybackPrefs?, audio: ProgramTrack?): ProgramTrack? {
        val subs = program?.subtitles.orEmpty()
        if (subs.isEmpty() || prefs == null) return null
        val firstWanted = {
            prefs.subLangs.map(::lang).firstNotNullOfOrNull { wanted -> subs.firstOrNull { lang(it.lang.ifEmpty { it.slot }) == wanted } }
        }
        return when (prefs.subMode) {
            PlaybackPrefs.SUB_ALWAYS -> firstWanted()
            PlaybackPrefs.SUB_FOREIGN -> {
                val audioLang = audio?.let { lang(it.lang.ifEmpty { it.slot }) }
                val understood = prefs.audioLangs.map(::lang)
                if (audioLang != null && audioLang !in understood) firstWanted() else null
            }
            else -> null
        }
    }

    fun select(program: ChannelProgram?, prefs: PlaybackPrefs?, choice: LiveTrackChoice): LiveTrackSelection {
        val audio = choice.audio?.let { picked -> program?.audio?.firstOrNull { it.slot == picked.slot } ?: picked }
            ?: defaultAudio(program, prefs)
        val subtitles = when (val picked = choice.subtitles) {
            SubtitleChoice.Off -> null
            is SubtitleChoice.On -> program?.subtitles?.firstOrNull { it.slot == picked.track.slot } ?: picked.track
            null -> defaultSubtitles(program, prefs, audio)
        }
        return LiveTrackSelection(audio, subtitles)
    }

    const val SLOT_RU = "ru"

    private val THREE_LETTER = mapOf("rus" to "ru", "eng" to "en", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de", "ukr" to "uk")
}
