package app.antresol.core.api

import app.antresol.core.model.MediaKey
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
