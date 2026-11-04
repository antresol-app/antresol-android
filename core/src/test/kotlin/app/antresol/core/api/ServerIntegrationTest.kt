package app.antresol.core.api

import app.antresol.core.model.BookKind
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Kind
import app.antresol.core.model.PlayMode
import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.model.Quality
import app.antresol.core.playback.CodecSupport
import app.antresol.core.playback.VideoSession
import app.antresol.core.playback.WebVtt
import app.antresol.core.playback.videoApi
import app.antresol.core.model.ShuffleSource
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Read-only checks against a real Antresol server with the sample library.
 * Runs only when `ANTRESOL_TEST_SERVER` is set: `1` means http://127.0.0.1:8420, anything
 * else is used as the base URL. Admin password comes from `ANTRESOL_TEST_PASSWORD` (default admin123).
 * Wrong-password sign-ins are deliberately not exercised here: the server throttles failed logins
 * per IP, which would lock out other clients of a shared test server (covered by mock tests instead).
 */
class ServerIntegrationTest {
    companion object {
        private const val DEVICE_ID = "antresol-android-core-tests"
        private var admin: AntresolClient? = null

        private fun baseUrl(): String? = System.getenv("ANTRESOL_TEST_SERVER")?.takeIf { it.isNotBlank() }?.let {
            if (it == "1") "http://127.0.0.1:8420" else it
        }

        private fun newClient() = AntresolClient(requireNotNull(baseUrl()), language = { ServerLanguage.EN })

        private fun device() = DeviceInfo(DEVICE_ID, "Core integration tests", "JVM", "test")

        @BeforeClass
        @JvmStatic
        fun signIn() {
            if (baseUrl() == null) return
            admin = newClient().also { client ->
                runBlocking {
                    val profile = client.profiles().first { it.hasPassword }
                    client.signIn(profile.id, System.getenv("ANTRESOL_TEST_PASSWORD") ?: "admin123", device())
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun signOut() {
            runBlocking { admin?.runCatching { logout() } }
        }
    }

    private fun client(): AntresolClient {
        assumeTrue("ANTRESOL_TEST_SERVER not set", baseUrl() != null)
        return requireNotNull(admin)
    }

    private fun AntresolClient.status(url: String, range: Boolean = false): Int {
        val request = Request.Builder().url(url).apply { if (range) header("Range", "bytes=0-1023") }.build()
        return httpClient.newCall(request).execute().use { it.code }
    }

    @Test
    fun authAndProfile() = runBlocking<Unit> {
        val client = client()
        val health = client.health()
        assertTrue(health.ok)
        assertEquals(32, health.serverId.length)
        assertFalse(client.setup().needsSetup)
        val me = client.me()
        assertTrue(me.isAdmin)
        assertTrue(client.devices().items.any { it.current })
        val key = client.mediaKey()
        assertTrue(key.key.startsWith("v1."))
        val cached = client.mediaKeys.get()
        assertTrue(cached.startsWith("v1."))
        assertEquals(cached, client.mediaKeys.get())
    }

    @Test
    fun kidProfileSignsInWithoutPassword() = runBlocking<Unit> {
        val base = client()
        val kidProfile = base.profiles().firstOrNull { !it.hasPassword }
        assumeTrue("no passwordless profile", kidProfile != null)
        val kid = newClient()
        val response = kid.signIn(kidProfile!!.id, null, device())
        try {
            assertEquals(kidProfile.id, response.user.id)
            assertEquals(response.user.isKid, kid.me().isKid)
            kid.home()
        } finally {
            kid.logout()
        }
    }

    @Test
    fun libraryBrowsing() = runBlocking<Unit> {
        val client = client()
        val home = client.home()
        assertTrue(home.shelves.isNotEmpty())
        val movies = client.items(kind = Kind.MOVIE, sort = "title")
        assertTrue(movies.items.isNotEmpty())
        assertTrue(movies.items.all { it.kind == Kind.MOVIE })
        val series = client.items(kind = Kind.SERIES)
        for (card in movies.items + series.items) {
            val detail = client.item(card.id)
            assertEquals(card.id, detail.id)
            for (season in detail.seasons) {
                client.item(season.id)
                season.episodes.forEach { assertEquals(it.id, client.item(it.id).id) }
            }
            client.progress(card.id)
        }
        val firstPoster = (movies.items + series.items).firstNotNullOfOrNull { client.urls.image(it.poster) }
        if (firstPoster != null) assertEquals(200, client.status(firstPoster))
        val search = client.search(movies.items.first().title)
        assertTrue(search.library.any { it.id == movies.items.first().id })
        client.libraryStatus()
    }

    @Test
    fun videoPlaybackDirectAndHls() = runBlocking<Unit> {
        val client = client()
        val files = client.items(kind = Kind.MOVIE).items.flatMap { client.item(it.id).files }
        assertTrue(files.isNotEmpty())
        val key = client.mediaKeys.get()

        // Baseline caps that include mkv: everything common plays directly.
        val direct = client.play(files.first().id, PlayRequest(PlaybackCaps.EXOPLAYER_BASELINE))
        if (direct.mode == PlayMode.DIRECT) {
            assertEquals(206, client.status(client.urls.playback(direct.url, key), range = true))
        } else {
            direct.hlsSession?.let { client.hlsStop(it) }
        }

        // A non-default audio track or an mp4-only player forces HLS.
        val file = files.firstOrNull { it.name.endsWith(".mkv") } ?: files.first()
        val hls = client.play(file.id, PlayRequest(PlaybackCaps(listOf("h264"), listOf("aac"), listOf("mp4")), quality = "auto"))
        assertTrue(hls.isHls)
        val session = requireNotNull(hls.hlsSession)
        try {
            assertEquals(200, client.status(client.urls.playback(hls.url, key)))
            client.hlsKeepalive(session)
            hls.subtitles.firstOrNull { it.url != null }?.let { sub ->
                assertTrue(client.subtitleVtt(sub.url!!).startsWith("WEBVTT"))
            }
        } finally {
            client.hlsStop(session)
        }
    }

    @Test
    fun videoSessionSwitchesTracksAndQuality() = runBlocking<Unit> {
        val client = client()
        val file = client.items(kind = Kind.MOVIE).items.flatMap { client.item(it.id).files }
            .firstOrNull { it.audio.size > 1 && it.subs.any { sub -> sub.url != null } }
        assumeTrue("no movie with two audio tracks and text subtitles", file != null)
        val session = VideoSession(client.videoApi(), file!!.id, CodecSupport.caps(setOf("video/avc", "video/hevc")))

        // mkv in caps: the default track plays directly, at the requested position.
        val direct = session.open(startMs = 20_000)
        assertEquals(PlayMode.DIRECT, direct.play.mode)
        assertEquals(20_000, direct.timeline.initialPlayerSeekMs)
        assertEquals(206, client.status(direct.url, range = true))

        // Another audio track forces HLS; the playlist starts at the keyframe before 20 s.
        val second = file.audio.first { !it.isDefault }.index
        val remux = session.open(startMs = 20_000, audioIndex = second)
        assertTrue(remux.isHls)
        assertTrue(remux.timeline.startMs in 1..20_000)
        val first = requireNotNull(session.session)
        val playlist = client.httpClient.newCall(Request.Builder().url(remux.url).build()).execute().use { it.body!!.string() }
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD") && playlist.contains("mk="))

        // Subtitle cues move to player time.
        val vtt = client.subtitleVtt(remux.play.subtitles.first { it.url != null }.url!!)
        val shifted = WebVtt.shift(vtt, remux.timeline.subtitleOffsetMs)
        assertTrue(shifted.startsWith("WEBVTT"))

        // A quality change replaces the session; the old one is gone.
        val lower = session.open(startMs = 25_000, quality = Quality.P480)
        assertEquals(PlayMode.TRANSCODE, lower.play.mode)
        assertEquals(second, lower.play.audioIndex)
        val replaced = runCatching { client.hlsKeepalive(first) }.exceptionOrNull() as? ApiException
        assertEquals(404, replaced?.status)

        // Background: the session is stopped and a later open starts a fresh one.
        val current = requireNotNull(session.session)
        session.suspendSession()
        assertEquals(404, (runCatching { client.hlsKeepalive(current) }.exceptionOrNull() as? ApiException)?.status)
        val reopened = session.open(startMs = 25_000)
        try {
            assertTrue(reopened.isHls)
            client.hlsKeepalive(requireNotNull(session.session))
        } finally {
            session.suspendSession()
        }
    }

    @Test
    fun music() = runBlocking<Unit> {
        val client = client()
        client.musicHome()
        val albums = client.albums(sort = "title")
        assertTrue(albums.items.isNotEmpty())
        val album = client.album(albums.items.first().id)
        assertTrue(album.tracks.isNotEmpty())
        val track = client.track(album.tracks.first().id)
        val key = client.mediaKeys.get()
        assertEquals(200, client.status(client.urls.track(track.id, mediaKey = key)))
        album.artist?.let { client.artist(it.id) }
        client.artists()
        client.songs()
        val genres = client.genres().genres
        client.shuffle()
        genres.firstOrNull()?.let { client.shuffle(ShuffleSource.Genre(it.name)) }
        client.playlists().items.firstOrNull()?.let { client.playlist(it.id) }
        val withLyrics = client.songs().items.firstOrNull { it.hasLyrics }
        if (withLyrics != null) assertNotNull(client.lyrics(withLyrics.id))
    }

    @Test
    fun booksAudiobooksComics() = runBlocking<Unit> {
        val client = client()
        client.booksHome()
        val key = client.mediaKeys.get()
        for (kind in listOf(BookKind.AUDIOBOOK, BookKind.BOOK, BookKind.COMIC)) {
            for (card in client.books(kind).items) {
                val detail = client.book(card.id)
                assertEquals(kind, detail.kind)
                if (kind == BookKind.AUDIOBOOK) {
                    assertEquals(detail.files.sumOf { it.durationMs }, detail.durationMs)
                    assertEquals(200, client.status(client.urls.audio(detail.files.first().id, mediaKey = key)))
                }
                for (volume in detail.volumes) {
                    client.book(volume.id)
                    val pages = client.comicPages(volume.fileId)
                    assertEquals(volume.pages, pages.count)
                    assertEquals(200, client.status(client.urls.comicPage(volume.fileId, 0)))
                }
            }
        }
    }
}
