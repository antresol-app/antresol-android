package app.antresol.core.playback

import app.antresol.core.model.AudioTrack

/** The film player's audio menu, as the web's `audioMenuOrder`. */
object AudioMenu {
    /**
     * The tracks as the server lists them (the file's own, the voice-over files beside it, the
     * cleaned ones), with each cleaned track moved right under the track it was made from. A cleaned
     * track whose source is missing stays where it is.
     */
    fun order(audio: List<AudioTrack>): List<AudioTrack> {
        fun placed(track: AudioTrack) = track.isCleaned && audio.any { it.index == track.cleanedFrom && !it.isCleaned }
        val out = mutableListOf<AudioTrack>()
        for (track in audio) {
            if (placed(track)) continue
            out += track
            if (!track.isCleaned) out += audio.filter { placed(it) && it.cleanedFrom == track.index }
        }
        return out
    }

    /** A row of the audio menu: the track shown, the track a press plays, whether it is the chosen one. */
    data class Row(val track: AudioTrack, val plays: AudioTrack, val checked: Boolean)

    /** «Очищенный перевод»: on or off, and the track it turns to. */
    data class Toggle(val on: Boolean, val target: AudioTrack)

    /** The menu with a cleaned voice-over as a switch on its voice, not a row of its own (as on the web). */
    data class CleanedSwitch(val rows: List<Row>, val toggle: Toggle?)

    fun cleanedSwitch(audio: List<AudioTrack>, selectedIndex: Int?): CleanedSwitch {
        fun paired(t: AudioTrack) = t.isCleaned && audio.any { it.index == t.cleanedFrom && !it.isCleaned }
        fun twin(base: Int) = audio.firstOrNull { it.isCleaned && it.cleanedFrom == base }
        val current = audio.firstOrNull { it.index == selectedIndex }
        val on = current != null && paired(current)
        val base = if (on) current?.cleanedFrom else selectedIndex
        val rows = order(audio).filterNot(::paired).map { t ->
            Row(t, if (on) twin(t.index) ?: t else t, t.index == base)
        }
        val toggle = base?.let { b -> twin(b)?.let { tw -> Toggle(on, if (on) audio.first { it.index == b } else tw) } }
        return CleanedSwitch(rows, toggle)
    }
}
