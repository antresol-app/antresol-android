package app.antresol.core.api

import app.antresol.core.model.MediaKey
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MediaUrlsTest {
    private val urls = MediaUrls("http://nas.local:8420/")

    @Test
    fun normalizesBaseUrls() {
        assertEquals("http://nas.local:8420", MediaUrls.normalizeBaseUrl("nas.local"))
        assertEquals("http://192.168.1.5:9000", MediaUrls.normalizeBaseUrl(" 192.168.1.5:9000/ "))
        assertEquals("https://media.example.com", MediaUrls.normalizeBaseUrl("https://media.example.com/"))
        assertEquals("http://127.0.0.1:8420", MediaUrls.normalizeBaseUrl("http://127.0.0.1:8420"))
    }

    @Test
    fun resolvesImagePaths() {
        assertNull(urls.image(""))
        assertNull(urls.image(null))
        assertEquals("http://nas.local:8420/api/images/local/19/poster", urls.image("/api/images/local/19/poster"))
        assertEquals("https://cdn/x.jpg", urls.image("https://cdn/x.jpg"))
        assertEquals("http://nas.local:8420/api/images/a%20b.jpg", urls.image("a b.jpg"))
    }

    @Test
    fun sizedImageTakesTheSmallestStepAtLeastAsWide() {
        val base = "http://nas.local:8420"
        val poster = "$base/api/images/local/19/poster"
        assertEquals("$poster?w=160", MediaUrls.sizedImage(base, poster, 96))
        assertEquals("$poster?w=160", MediaUrls.sizedImage(base, poster, 160))
        assertEquals("$poster?w=240", MediaUrls.sizedImage(base, poster, 161))
        assertEquals("$poster?w=480", MediaUrls.sizedImage(base, poster, 330))
        assertEquals("$poster?w=1920", MediaUrls.sizedImage(base, poster, 1920))
        // A different width is a different URL, so a small cached copy is never drawn in a bigger view.
        assertEquals(MediaUrls.IMAGE_WIDTHS.size, MediaUrls.IMAGE_WIDTHS.map { MediaUrls.sizedImage(base, poster, it) }.toSet().size)
    }

    @Test
    fun sizedImageKeepsTheUrlWhenNoStepFitsOrTheImageHasOneSize() {
        val base = "http://nas.local:8420"
        val poster = "$base/api/images/a%20b.jpg"
        assertEquals(poster, MediaUrls.sizedImage(base, poster, 1921)) // wider than every step: the original
        assertEquals(poster, MediaUrls.sizedImage(base, poster, 0))
        val frame = "$base/api/images/frame/42"
        assertEquals(frame, MediaUrls.sizedImage(base, frame, 320)) // video frames have one fixed size
        assertEquals("$poster?v=2", MediaUrls.sizedImage(base, "$poster?v=2", 320))
        assertEquals("https://cdn/x.jpg", MediaUrls.sizedImage(base, "https://cdn/x.jpg", 320))
        assertEquals("http://other:8420/api/images/p", MediaUrls.sizedImage(base, "http://other:8420/api/images/p", 320))
        assertEquals("$base/api/comics/7/page/0", MediaUrls.sizedImage(base, "$base/api/comics/7/page/0", 320))
    }

    @Test
    fun aPictureThatMayBeTransparentIsNeverSized() {
        // The server's smaller copies are JPEG: a transparent logo would come back on black.
        val base = "http://nas.local:8420"
        val logo = "$base/api/images/local/19/logo"
        assertEquals(logo, MediaUrls.sizedImage(base, logo, 320, transparent = true))
        assertEquals("$logo?w=320", MediaUrls.sizedImage(base, logo, 320))
    }

    @Test
    fun neededTileWidthIsTheLargerSideOfACroppedTile() {
        assertEquals(450, MediaUrls.neededTileWidth(300, 450, crop = true)) // a square cover fills a poster tile
        assertEquals(480, MediaUrls.neededTileWidth(480, 270, crop = true)) // a wide tile: its width
        assertEquals(200, MediaUrls.neededTileWidth(200, 200, crop = true))
        assertEquals(300, MediaUrls.neededTileWidth(300, 450, crop = false)) // fitted inside: never wider than the box
    }

    @Test
    fun anEpisodesStillInATallTileIsAskedForAtItsCutWidth() {
        // An episode's poster may be its 16:9 still: in a poster or square tile it is not the tile's shape.
        assertEquals(false, MediaUrls.artworkFitsTile("episode", wideTile = false))
        assertTrue(MediaUrls.artworkFitsTile("episode", wideTile = true))
        assertTrue(MediaUrls.artworkFitsTile("movie", wideTile = false))
        assertTrue(MediaUrls.artworkFitsTile("album", wideTile = false))
        // What it then needs in a 200 by 300 tile: the still's width at the tile's height, not the tile's larger side.
        assertEquals(534, MediaUrls.neededImageWidth(200, 300, crop = true))
        assertEquals(300, MediaUrls.neededTileWidth(200, 300, crop = true))
    }

    @Test
    fun neededImageWidthCoversACroppedWidePicture() {
        assertEquals(300, MediaUrls.neededImageWidth(300, 450, crop = false))
        // A 16:9 backdrop cut to a tall box needs the box's height in 16:9.
        assertEquals(3200, MediaUrls.neededImageWidth(1080, 1800, crop = true))
        assertEquals(1920, MediaUrls.neededImageWidth(1920, 1080, crop = true))
        assertEquals(2000, MediaUrls.neededImageWidth(2000, 900, crop = true))
    }

    @Test
    fun buildsMediaUrls() {
        assertEquals("http://nas.local:8420/api/stream/12?mk=k1", urls.stream(12, "k1"))
        assertEquals("http://nas.local:8420/api/download/12", urls.download(12))
        assertEquals(
            "http://nas.local:8420/api/hls/abc/index.m3u8?mk=v1.2.1.9.x",
            urls.playback("/api/hls/abc/index.m3u8", "v1.2.1.9.x"),
        )
        assertEquals(
            "http://nas.local:8420/api/tracks/15/stream?accept=aac,mp3,flac,opus,vorbis&mk=k",
            urls.track(15, mediaKey = "k"),
        )
        assertEquals(
            "http://nas.local:8420/api/audio/11?accept=mp3&format=aac&t=12.500",
            urls.audio(11, accept = listOf("mp3"), format = "aac", startSec = 12.5),
        )
        assertEquals("http://nas.local:8420/api/audio/11?accept=aac&t=30", urls.audio(11, listOf("aac"), startSec = 30.0))
        assertEquals("http://nas.local:8420/api/comics/13/page/0", urls.comicPage(13, 0))
        assertEquals("http://nas.local:8420/api/channels/live/2/index.m3u8?mk=v1.2.1.9.x", urls.liveChannel(2, "v1.2.1.9.x"))
        assertEquals("http://nas.local:8420/api/channels/live/2/index.m3u8", urls.liveChannel(2))
    }

    @Test
    fun mediaKeyCacheRefreshesNearExpiry() = runTest {
        var now = Instant.parse("2026-11-04T20:00:00Z")
        var fetches = 0
        val cache = MediaKeyCache(
            fetch = { fetches++; MediaKey("key$fetches", "2026-11-05T08:00:00Z") },
            now = { now },
        )
        assertEquals("key1", cache.get())
        now = Instant.parse("2026-11-05T06:30:00Z")
        assertEquals("key1", cache.get())
        now = Instant.parse("2026-11-05T07:30:00Z")
        assertEquals("key2", cache.get())
        cache.invalidate()
        assertEquals("key3", cache.get())
        now = Instant.parse("2026-11-05T05:00:00Z")
        assertEquals("key4", cache.get(minRemaining = Duration.ofHours(4)))
    }

    @Test
    fun mediaKeyCacheRefetchesForAnotherToken() = runTest {
        var token = "admin"
        var fetches = 0
        val cache = MediaKeyCache(
            fetch = { fetches++; MediaKey("$token-$fetches", "2026-11-05T08:00:00Z") },
            token = { token },
            now = { Instant.parse("2026-11-04T20:00:00Z") },
        )
        assertEquals("admin-1", cache.get())
        assertEquals("admin-1", cache.get())
        token = "kid"
        assertEquals("kid-2", cache.get())
    }

    @Test
    fun interceptorOnlyAuthenticatesOwnServerAndSkipsSignedUrls() {
        val server = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() }
        try {
            val http = OkHttpClient.Builder()
                .addInterceptor(AntresolHeadersInterceptor({ server.url("/").toString() }, { "tok" }, { "en" }))
                .build()
            repeat(2) { server.enqueue(MockResponse()) }
            other.enqueue(MockResponse())
            http.newCall(Request.Builder().url(server.url("/api/images/local/1/poster")).header("Origin", "x").build()).execute().close()
            http.newCall(Request.Builder().url(server.url("/api/stream/1?mk=k")).build()).execute().close()
            http.newCall(Request.Builder().url(other.url("/img.jpg")).build()).execute().close()

            val image = server.takeRequest()
            assertEquals("Bearer tok", image.getHeader("Authorization"))
            assertEquals("en", image.getHeader("Accept-Language"))
            assertNull(image.getHeader("Origin"))
            assertNull(server.takeRequest().getHeader("Authorization"))
            assertNull(other.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
            other.shutdown()
        }
    }
}
