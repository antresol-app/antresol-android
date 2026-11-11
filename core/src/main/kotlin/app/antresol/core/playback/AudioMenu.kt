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
}
