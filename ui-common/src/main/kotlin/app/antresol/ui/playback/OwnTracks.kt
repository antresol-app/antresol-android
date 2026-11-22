package app.antresol.ui.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import app.antresol.core.model.AudioTrack
import app.antresol.core.model.SubTrack

/**
 * The tracks inside a stream (what ExoPlayer found in it), as the player's menus list them for a stream an addon
 * serves whose answer lists none: [AudioTrack.index] and [SubTrack.index] are their order among the stream's own.
 */
internal object OwnTracks {
    /** The groups of [type] the stream has itself, in order: supported ones only, and never the side-loaded subtitle. */
    fun groups(tracks: Tracks, type: Int, sideLoadedId: String): List<Tracks.Group> = tracks.groups.filter { group ->
        group.type == type && group.isSupported(false) &&
            (0 until group.length).none { group.getTrackFormat(it).id?.contains(sideLoadedId) == true }
    }

    fun audio(groups: List<Tracks.Group>): List<AudioTrack> = groups.mapIndexed { order, group ->
        val format = group.getTrackFormat(0)
        AudioTrack(
            index = order,
            codec = codecOf(format.sampleMimeType),
            lang = format.language.orEmpty(),
            title = format.label.orEmpty(),
            channels = format.channelCount.coerceAtLeast(0),
        )
    }

    fun text(groups: List<Tracks.Group>): List<SubTrack> = groups.mapIndexed { order, group ->
        val format = group.getTrackFormat(0)
        SubTrack(
            index = order,
            codec = codecOf(format.sampleMimeType),
            lang = format.language.orEmpty(),
            title = format.label.orEmpty(),
            forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
        )
    }

    /** The index of the group that plays, or -1. */
    fun selected(groups: List<Tracks.Group>): Int = groups.indexOfFirst { it.isSelected }

    /** The codec by the name the server's own tracks use (`audio/mp4a-latm` → `aac`), else the mime's subtype. */
    private fun codecOf(mime: String?): String = when (mime) {
        null -> ""
        MimeTypes.AUDIO_AAC -> "aac"
        MimeTypes.AUDIO_AC3 -> "ac3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "dts"
        MimeTypes.AUDIO_TRUEHD -> "truehd"
        MimeTypes.AUDIO_OPUS -> "opus"
        MimeTypes.AUDIO_VORBIS -> "vorbis"
        MimeTypes.AUDIO_FLAC -> "flac"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "mp3"
        else -> mime.substringAfter('/').substringBefore('.')
    }
}
