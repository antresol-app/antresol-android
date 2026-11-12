package app.antresol.ui.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live player picks audio and subtitles by the programme's `name`, compared with the track's
 * Format.label: the HLS parser must turn each rendition's NAME into that label (master of the spec).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveRenditionsTest {
    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Русский",LANGUAGE="ru",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="audio-ru.m3u8?mk=k"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Русский 2",LANGUAGE="ru",DEFAULT=NO,AUTOSELECT=NO,CHANNELS="2",URI="audio-ru2.m3u8?mk=k"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,CHANNELS="2",URI="audio-en.m3u8?mk=k"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="Русские",LANGUAGE="ru",DEFAULT=NO,AUTOSELECT=YES,FORCED=NO,URI="subs-ru.m3u8?mk=k"
        #EXT-X-STREAM-INF:BANDWIDTH=7000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud",SUBTITLES="sub",CLOSED-CAPTIONS=NONE
        video.m3u8?mk=k
    """.trimIndent()

    @Test
    fun renditionNamesBecomeTrackLabels() {
        val playlist = HlsPlaylistParser().parse(Uri.parse("http://server/api/channels/live/1/index.m3u8"), master.byteInputStream())
            as HlsMultivariantPlaylist
        assertEquals(listOf("Русский", "Русский 2", "English"), playlist.audios.map { it.format.label })
        assertEquals(listOf("Русские"), playlist.subtitles.map { it.format.label })
    }
}
