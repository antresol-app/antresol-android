package app.antresol.core.channels

import app.antresol.core.model.ProgramTrack

/**
 * One audio or subtitle track of an external stream as the player found it: its group among the
 * stream's groups of that type ([group]), the stream's own [label] (HLS `NAME`) and [language].
 * [mimeType] tells closed captions apart from subtitles with the same name.
 */
data class StreamTrack(
    val group: Int,
    val label: String?,
    val language: String?,
    val channels: Int = 0,
    val mimeType: String? = null,
)

/**
 * The picker's tracks of an external stream (spec goal 10: what the stream itself has). External
 * channels have no guide entry listing their renditions, so the options come from the stream's
 * track groups, labelled with the stream's own names; two groups with the same name get a
 * distinguishing suffix (closed captions «(CC)», surround «5.1», else a number), so every option
 * is unique by [ProgramTrack.name]; closed captions come after the subtitles. [ProgramTrack.slot]
 * keeps the group index for the override.
 */
object ExternalTracks {
    fun options(tracks: List<StreamTrack>, fallbackName: (index: Int, language: String?) -> String): List<ProgramTrack> {
        val base = tracks.mapIndexed { index, track ->
            track.label?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName(index, track.language)
        }
        val counts = base.groupingBy { it }.eachCount()
        val used = mutableSetOf<String>()
        return tracks.mapIndexed { index, track ->
            var name = base[index]
            if ((counts[name] ?: 0) > 1) {
                name = when {
                    isCaption(track) -> "$name (CC)"
                    track.channels > 2 -> "$name ${surround(track.channels)}"
                    else -> name
                }
            }
            var unique = name
            var n = 2
            while (!used.add(unique)) unique = "$name ${n++}"
            ProgramTrack(slot = "g${track.group}", name = unique, label = unique, lang = track.language.orEmpty()) to isCaption(track)
        }.sortedBy { it.second }.map { it.first } // Closed captions after the real subtitles.
    }

    private fun isCaption(track: StreamTrack): Boolean =
        track.mimeType?.contains("cea-608") == true || track.mimeType?.contains("cea-708") == true

    /** The group index of an option made by [options]. */
    fun group(track: ProgramTrack): Int? = track.slot.removePrefix("g").takeIf { track.slot.startsWith("g") }?.toIntOrNull()

    private fun surround(channels: Int): String = when (channels) {
        6 -> "5.1"
        8 -> "7.1"
        else -> "${channels}ch"
    }
}

/**
 * The viewer's track choice per external channel, kept on the device (spec goal 10: «выбор
 * запоминается на канал»), never on the server. A choice is the language and the stream's label,
 * matched again by [LiveTracks.match] (the same label, else the same language).
 */
interface ExternalTrackMemory {
    fun get(channelId: Long): LiveTrackChoice?

    fun put(channelId: Long, choice: LiveTrackChoice)
}

/** Only what identifies a choice across sessions: language + label (no group index, it may move). */
fun LiveTrackChoice.forMemory(): LiveTrackChoice = LiveTrackChoice(
    audio = audio?.let { ProgramTrack(name = it.name, label = it.label, lang = it.lang) },
    subtitles = when (val s = subtitles) {
        is SubtitleChoice.On -> SubtitleChoice.On(ProgramTrack(name = s.track.name, label = s.track.label, lang = s.track.lang))
        else -> s
    },
)
