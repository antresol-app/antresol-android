package app.antresol.ui.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tracks inside an addon's stream, as the audio and subtitle menus list them when its answer lists none. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnTracksTest {
    private fun group(mime: String, lang: String?, label: String?, selected: Boolean = false, id: String? = null, channels: Int = Format.NO_VALUE, supported: Boolean = true) =
        Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType(mime).setLanguage(lang).setLabel(label).setId(id).setChannelCount(channels).build()),
            false,
            intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_EXCEEDS_CAPABILITIES),
            booleanArrayOf(selected),
        )

    private val tracks = Tracks(
        listOf(
            group(MimeTypes.VIDEO_H264, null, null),
            group(MimeTypes.AUDIO_AAC, "eng", "Original", selected = true, channels = 6),
            group(MimeTypes.AUDIO_AC3, "rus", null, channels = 2),
            group(MimeTypes.AUDIO_DTS, "fra", "Cannot play", supported = false),
            group(MimeTypes.TEXT_VTT, "eng", "English"),
            group(MimeTypes.TEXT_VTT, "rus", null, id = "antresol-subtitle"),
        ),
    )

    @Test
    fun theAudioTracksAreTheSupportedOnesInOrder() {
        val groups = OwnTracks.groups(tracks, C.TRACK_TYPE_AUDIO, "antresol-subtitle")
        val audio = OwnTracks.audio(groups)
        // The player normalises the language code to two letters.
        assertEquals(listOf("en", "ru"), audio.map { it.lang })
        assertEquals(listOf(0, 1), audio.map { it.index })
        assertEquals(listOf("Original", ""), audio.map { it.title })
        assertEquals(listOf(6, 2), audio.map { it.channels })
        assertEquals(listOf("aac", "ac3"), audio.map { it.codec })
        // The first plays.
        assertEquals(0, OwnTracks.selected(groups))
    }

    @Test
    fun theSideLoadedSubtitleIsNotOneOfTheStreamsOwn() {
        val groups = OwnTracks.groups(tracks, C.TRACK_TYPE_TEXT, "antresol-subtitle")
        val text = OwnTracks.text(groups)
        assertEquals(listOf("English"), text.map { it.title })
        assertEquals(listOf("vtt"), text.map { it.codec })
        // None shows until one is picked.
        assertEquals(-1, OwnTracks.selected(groups))
    }

    @Test
    fun aStreamWithoutTracksOffersNone() {
        val none = Tracks(listOf(group(MimeTypes.VIDEO_H264, null, null)))
        assertEquals(emptyList<Any>(), OwnTracks.audio(OwnTracks.groups(none, C.TRACK_TYPE_AUDIO, "x")))
        assertEquals(emptyList<Any>(), OwnTracks.text(OwnTracks.groups(none, C.TRACK_TYPE_TEXT, "x")))
    }
}
