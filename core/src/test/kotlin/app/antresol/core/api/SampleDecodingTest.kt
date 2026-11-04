package app.antresol.core.api

import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.ComicPages
import app.antresol.core.model.DeviceSessions
import app.antresol.core.model.ErrorBody
import app.antresol.core.model.GenresResponse
import app.antresol.core.model.Health
import app.antresol.core.model.HlsStatus
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.LibraryStatus
import app.antresol.core.model.Lyrics
import app.antresol.core.model.Me
import app.antresol.core.model.MediaKey
import app.antresol.core.model.Page
import app.antresol.core.model.PlayMode
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.Playlist
import app.antresol.core.model.PlaylistDetail
import app.antresol.core.model.PlaylistList
import app.antresol.core.model.Profile
import app.antresol.core.model.Progress
import app.antresol.core.model.SearchResults
import app.antresol.core.model.SetupStatus
import app.antresol.core.model.ShelvesResponse
import app.antresol.core.model.TokenResponse
import app.antresol.core.model.Track
import app.antresol.core.model.TrackList
import app.antresol.core.model.UserAccount
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Decodes every real server response in `src/test/resources/api-samples` (copied from the spec). */
class SampleDecodingTest {
    private val json = AntresolClient.json

    private val decoders: Map<String, KSerializer<*>> = mapOf(
        "auth-media-key" to serializer<MediaKey>(),
        "auth-token" to serializer<TokenResponse>(),
        "book-audiobook-mp3" to serializer<BookDetail>(),
        "book-audiobook-multifile" to serializer<BookDetail>(),
        "book-audiobook" to serializer<BookDetail>(),
        "book-comic-volume" to serializer<BookDetail>(),
        "book-comic" to serializer<BookDetail>(),
        "books-audiobooks" to serializer<Page<Card>>(),
        "books-comics" to serializer<Page<Card>>(),
        "books-home" to serializer<ShelvesResponse>(),
        "comic-pages" to serializer<ComicPages>(),
        "error-401" to serializer<ErrorBody>(),
        "health" to serializer<Health>(),
        "hls-keepalive" to serializer<HlsStatus>(),
        "home" to serializer<HomeResponse>(),
        "item-episode" to serializer<ItemDetail>(),
        "item-movie" to serializer<ItemDetail>(),
        "item-season" to serializer<ItemDetail>(),
        "item-series" to serializer<ItemDetail>(),
        "items-movies" to serializer<Page<Card>>(),
        "items-series" to serializer<Page<Card>>(),
        "library-status" to serializer<LibraryStatus>(),
        "me-devices" to serializer<DeviceSessions>(),
        "me" to serializer<Me>(),
        "music-album" to serializer<AlbumDetail>(),
        "music-albums" to serializer<Page<Card>>(),
        "music-artist" to serializer<ArtistDetail>(),
        "music-artists" to serializer<Page<Card>>(),
        "music-genres" to serializer<GenresResponse>(),
        "music-home" to serializer<ShelvesResponse>(),
        "music-lyrics" to serializer<Lyrics>(),
        "music-shuffle" to serializer<TrackList>(),
        "music-songs" to serializer<Page<Track>>(),
        "music-track" to serializer<Track>(),
        "play-direct" to serializer<PlayResponse>(),
        "play-hevc-fmp4" to serializer<PlayResponse>(),
        "play-mkv-hls" to serializer<PlayResponse>(),
        "play-transcode-pixfmt" to serializer<PlayResponse>(),
        "play-transcode-quality" to serializer<PlayResponse>(),
        "playlist-create" to serializer<Playlist>(),
        "playlist-update" to serializer<Playlist>(),
        "playlist" to serializer<PlaylistDetail>(),
        "playlists" to serializer<PlaylistList>(),
        "profiles" to ListSerializer(serializer<Profile>()),
        "progress-get" to serializer<Progress>(),
        "search-music" to serializer<SearchResults>(),
        "search" to serializer<SearchResults>(),
        "setup-get" to serializer<SetupStatus>(),
        "users-create" to serializer<UserAccount>(),
        "users-list" to ListSerializer(serializer<UserAccount>()),
    )

    private fun samplesDir(): File {
        val url = requireNotNull(javaClass.classLoader.getResource("api-samples")) { "api-samples missing" }
        return File(url.toURI())
    }

    private inline fun <reified T> sample(name: String): T =
        json.decodeFromString(serializer<T>(), File(samplesDir(), "$name.json").readText())

    @Test
    fun everyJsonSampleHasADecoderAndDecodes() {
        val files = samplesDir().listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no samples found", files.isNotEmpty())
        for (file in files) {
            val name = file.nameWithoutExtension
            val decoder = decoders[name]
            assertNotNull("No decoder registered for sample $name", decoder)
            val decoded = runCatching { json.decodeFromString(decoder!!, file.readText()) }
            assertTrue("Failed to decode $name: ${decoded.exceptionOrNull()}", decoded.isSuccess)
        }
        assertEquals(files.map { it.nameWithoutExtension }.toSet(), decoders.keys)
    }

    @Test
    fun movieDetail() {
        val movie = sample<ItemDetail>("item-movie")
        assertEquals(19L, movie.id)
        assertEquals("movie", movie.kind)
        assertEquals(2019, movie.year)
        assertEquals(listOf("1080p", "Audio EN RU", "Subtitles EN"), movie.badges)
        val file = movie.files.single()
        assertEquals(12L, file.id)
        assertEquals(60046L, file.durationMs)
        assertEquals(2, file.audio.size)
        assertTrue(file.audio[0].isDefault)
        assertEquals("eng", file.audio[0].lang)
        assertEquals("/api/subs/12/1000.vtt?src=b70596c2", file.subs[1].url)
        assertTrue(file.subs[1].external)
        assertTrue(movie.inWatchlist)
        assertTrue(movie.actions.play)
        assertEquals(movie.id, movie.toCard().id)
    }

    @Test
    fun seriesDetailHasSeasonsAndNext() {
        val series = sample<ItemDetail>("item-series")
        assertNull(series.year)
        assertEquals(2, series.seasons.size)
        val episode = series.seasons[0].episodes[0]
        assertEquals(1, episode.seasonNo)
        assertEquals(1, episode.episodeNo)
        assertEquals(2L, episode.seriesId)
        assertEquals(20023L, episode.durationMs)
        assertFalse(episode.watched)
        assertEquals(4L, series.next?.id)
        assertTrue(series.files.isEmpty())
    }

    @Test
    fun homeShelves() {
        val home = sample<HomeResponse>("home")
        assertEquals(19L, home.hero.first().id)
        assertEquals("continue", home.shelves.first().id)
        val episode = home.shelves.first().cards.first { it.kind == "episode" }
        assertEquals(2L, episode.seriesId)
        assertTrue(home.shelves.first().cards.first().progress > 0.3)
    }

    @Test
    fun playResponses() {
        val direct = sample<PlayResponse>("play-direct")
        assertEquals(PlayMode.DIRECT, direct.mode)
        assertFalse(direct.isHls)
        assertNull(direct.hlsSession)

        val hls = sample<PlayResponse>("play-mkv-hls")
        assertEquals(PlayMode.REMUX, hls.mode)
        assertEquals("d4efc029ebb8590d", hls.hlsSession)
        assertTrue(hls.hlsComplete)
        assertEquals("original", hls.quality.applied)
        assertEquals(1, hls.audioIndex)
        assertEquals(2, hls.subtitles.size)
    }

    @Test
    fun audiobookAndComics() {
        val book = sample<BookDetail>("book-audiobook")
        assertEquals("audiobook", book.kind)
        assertEquals(listOf("Jane Writer"), book.authors)
        assertNull(book.series)
        assertNull(book.progress)
        assertEquals(3, book.chapters.size)
        assertEquals(20000L, book.chapters[1].startMs)

        val multi = sample<BookDetail>("book-audiobook-multifile")
        assertEquals(2, multi.files.size)
        assertEquals(multi.files.sumOf { it.durationMs }, multi.durationMs)

        val comic = sample<BookDetail>("book-comic")
        val volume = comic.volumes.single()
        assertEquals(13L, volume.fileId)
        assertEquals(3, volume.pages)
        assertEquals("rtl", volume.direction)

        val vol = sample<BookDetail>("book-comic-volume")
        assertEquals(1.0, vol.series?.index ?: 0.0, 0.0)
        assertEquals(13L, vol.readFileId)

        val pages = sample<ComicPages>("comic-pages")
        assertEquals(3, pages.count)
        assertTrue(pages.isRightToLeft)
        assertEquals(listOf(800, 1200), pages.sizes[0])
    }

    @Test
    fun music() {
        val album = sample<AlbumDetail>("music-album")
        assertEquals(13L, album.artist?.id)
        assertEquals("Lossless · FLAC", album.quality)
        assertEquals(3, album.tracks.size)
        assertTrue(album.tracks[0].hasLyrics)

        val playlist = sample<PlaylistDetail>("playlist")
        assertEquals(listOf(0, 1, 2), playlist.tracks.map { it.idx })

        val lyrics = sample<Lyrics>("music-lyrics")
        assertTrue(lyrics.synced)
        assertEquals(5.0, lyrics.lines[1].t, 0.0)
    }

    @Test
    fun authAndProfiles() {
        val profiles = sample<List<Profile>>("profiles")
        assertEquals(listOf("Admin", "Kiddo"), profiles.map { it.name })
        assertTrue(profiles[0].hasPassword)

        val token = sample<TokenResponse>("auth-token")
        assertEquals(43, token.token.length)
        assertTrue(token.user.isAdmin)
        assertNull(token.user.maxRating)

        val users = sample<List<UserAccount>>("users-list")
        assertEquals("6+", users[1].maxRating)

        val progress = sample<Progress>("progress-get")
        assertTrue(progress.hasProgress)
        assertEquals(15000L, progress.positionMs)
    }

    @Test
    fun toleratesUnknownKeysAndNulls() {
        val card = json.decodeFromString(
            serializer<Card>(),
            """{"id":1,"kind":"movie","title":null,"badges":null,"futureField":{"x":1}}""",
        )
        assertEquals("", card.title)
        assertTrue(card.badges.isEmpty())
    }
}
