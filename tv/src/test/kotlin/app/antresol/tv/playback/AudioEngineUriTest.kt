package app.antresol.tv.playback

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.api.MediaUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What an item that crossed the media session's boundary becomes: its stream URL is built from its
 * media id on this side; a URI sent by a controller is never kept.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioEngineUriTest {
    private val urls = MediaUrls("http://server.test")

    private fun item(id: String, uri: String? = null) = MediaItem.Builder().setMediaId(id).apply { uri?.let(::setUri) }.build()

    private fun uriOf(item: MediaItem?) = item?.localConfiguration?.uri?.toString()

    @Test
    fun aTrackAndABookPartGetTheirOwnStreamUrls() {
        assertEquals(urls.track(7), uriOf(AudioEngine.withUri(item("track:7:3"), urls)))
        assertEquals(urls.audio(42), uriOf(AudioEngine.withUri(item("part:42:0"), urls)))
    }

    @Test
    fun aTranscodedStreamStartsLater() {
        assertEquals(urls.track(7, startSec = 12.5), uriOf(AudioEngine.withUri(item("track:7:3"), urls, startSec = 12.5)))
    }

    @Test
    fun aUriTheControllerSentIsReplaced() {
        val sent = item("track:7:3", uri = "http://elsewhere.test/evil.mp3")
        assertEquals(urls.track(7), uriOf(AudioEngine.withUri(sent, urls)))
    }

    @Test
    fun anIdThisAppNeverMadeIsDropped() {
        assertNull(AudioEngine.withUri(item("http://elsewhere.test/evil.mp3", uri = "http://elsewhere.test/evil.mp3"), urls))
        assertNull(AudioEngine.withUri(item("track:x:1"), urls))
        assertNull(AudioEngine.withUri(item("part:"), urls))
        assertNull(AudioEngine.withUri(item(""), urls))
    }
}
