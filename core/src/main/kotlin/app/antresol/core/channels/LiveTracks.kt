package app.antresol.core.channels

import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.ProgramTrack

/**
 * The viewer's own audio / subtitle choice on a channel: kept per channel on the device (an
 * [ExternalTrackMemory], for Antresol's channels and external ones alike), it sticks across the
 * channel's programmes by the rendition's `name` (falling back by language), and is never sent to
 * the server (TV is TV: no track memory there). Null fields mean «the default».
 */
data class LiveTrackChoice(
    /** The audio rendition picked (its `name` and `lang` are what sticks). */
    val audio: ProgramTrack? = null,
    /** The subtitles picked: a track, [SubtitleChoice.Off], or null for the default. */
    val subtitles: SubtitleChoice? = null,
)

sealed interface SubtitleChoice {
    data object Off : SubtitleChoice

    data class On(val track: ProgramTrack) : SubtitleChoice
}

/** What the player selects for a programme: the HLS rendition NAMEs ([subtitles] null = off). */
data class LiveTrackSelection(val audio: ProgramTrack?, val subtitles: ProgramTrack?)

/**
 * Picks the live stream's renditions for a programme (round 5 spec «Every audio and subtitle
 * track»). The master carries up to 8 audio and 6 subtitle renditions with arbitrary names, in no
 * fixed order; the programme's `audio`/`subtitles` list the ones it really has. Nothing here
 * assumes a name, a count or a position:
 * - audio = the first of the device's languages ([select]'s `system`: the system locale list) the
 *   programme has, else the first of the profile's `audioLangs`, else the original (a track named
 *   so), else its first — the interface language stays the profile's;
 * - subtitles by `subMode` (`off`; `always`: the first of `subLangs` present; `foreign`: when the
 *   audio's language is not one of `audioLangs`);
 * - the viewer's [LiveTrackChoice] wins: the programme's rendition with the same `name`, else its
 *   first one in the same language, else the default for this programme (the choice is kept and
 *   comes back on the next programme that has it).
 */
object LiveTracks {
    /** `ru`/`rus`/`ru-RU` → `ru`; the prefs and the renditions compare two-letter codes. */
    fun lang(code: String): String {
        val base = code.lowercase().substringBefore('-').substringBefore('_')
        return THREE_LETTER[base] ?: base
    }

    /** The language of [track] (its BCP 47 `lang`; older servers only had the slot). */
    fun langOf(track: ProgramTrack): String = lang(track.lang.ifEmpty { track.slot })

    fun defaultAudio(program: ChannelProgram?, prefs: PlaybackPrefs?, system: List<String> = emptyList()): ProgramTrack? {
        val audio = program?.audio.orEmpty()
        if (audio.isEmpty()) return null
        (system + prefs?.audioLangs.orEmpty()).map(::lang).filter { it.isNotEmpty() }.forEach { wanted ->
            audio.firstOrNull { langOf(it) == wanted }?.let { return it }
        }
        return audio.firstOrNull { ORIGINAL.containsMatchIn(it.name) || ORIGINAL.containsMatchIn(it.label) } ?: audio.first()
    }

    fun defaultSubtitles(program: ChannelProgram?, prefs: PlaybackPrefs?, audio: ProgramTrack?): ProgramTrack? {
        val subs = program?.subtitles.orEmpty()
        if (subs.isEmpty() || prefs == null) return null
        val firstWanted = {
            prefs.subLangs.map(::lang).firstNotNullOfOrNull { wanted -> subs.firstOrNull { langOf(it) == wanted } }
        }
        return when (prefs.subMode) {
            PlaybackPrefs.SUB_ALWAYS -> firstWanted()
            PlaybackPrefs.SUB_FOREIGN -> {
                val audioLang = audio?.let(::langOf)
                val understood = prefs.audioLangs.map(::lang)
                if (audioLang != null && audioLang !in understood) firstWanted() else null
            }
            else -> null
        }
    }

    /**
     * [picked]'s counterpart among [tracks]: the same `name`, else the first in its language; null
     * when [tracks] has neither.
     */
    fun match(tracks: List<ProgramTrack>, picked: ProgramTrack): ProgramTrack? =
        tracks.firstOrNull { it.name == picked.name }
            ?: langOf(picked).takeIf { it.isNotEmpty() }?.let { wanted -> tracks.firstOrNull { langOf(it) == wanted } }

    fun select(program: ChannelProgram?, prefs: PlaybackPrefs?, choice: LiveTrackChoice, system: List<String> = emptyList()): LiveTrackSelection {
        val audioTracks = program?.audio
        val audio = choice.audio?.let { picked ->
            // Unknown tracks (no programme, or an older server): keep the pick as it is.
            if (audioTracks.isNullOrEmpty()) picked else match(audioTracks, picked)
        } ?: defaultAudio(program, prefs, system)
        val subtitleTracks = program?.subtitles
        val subtitles = when (val picked = choice.subtitles) {
            SubtitleChoice.Off -> null
            // No such subtitles in this programme: none rather than another language's cues.
            is SubtitleChoice.On -> if (subtitleTracks == null) picked.track else match(subtitleTracks, picked.track)
            null -> defaultSubtitles(program, prefs, audio)
        }
        return LiveTrackSelection(audio, subtitles)
    }

    const val RU = "ru"

    private val ORIGINAL = Regex("оригинал|original", RegexOption.IGNORE_CASE)

    private val THREE_LETTER = mapOf(
        "rus" to "ru", "eng" to "en", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de", "ukr" to "uk",
        "spa" to "es", "ita" to "it", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh", "por" to "pt",
    )
}
