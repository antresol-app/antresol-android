package app.antresol.core.api

import app.antresol.core.model.AddonRef
import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.AppInfo
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestCount
import app.antresol.core.model.RequestStatus
import app.antresol.core.model.TrailerList
import app.antresol.core.model.Trailer
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelGuide
import app.antresol.core.model.ChannelList
import app.antresol.core.model.ComicPages
import app.antresol.core.model.DeviceSessions
import app.antresol.core.model.EpisodeGuideResponse
import app.antresol.core.model.ErrorBody
import app.antresol.core.model.GenresResponse
import app.antresol.core.model.Health
import app.antresol.core.model.HlsStatus
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.InputInfo
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
import app.antresol.core.model.RunResult
import app.antresol.core.model.SearchResults
import app.antresol.core.model.SetupStatus
import app.antresol.core.model.TitleInfo
import app.antresol.core.model.ShelvesResponse
import app.antresol.core.model.TokenResponse
import app.antresol.core.model.Track
import app.antresol.core.model.TrackList
import app.antresol.core.model.UserAccount
import app.antresol.core.model.WayScope
import app.antresol.core.model.WayRole
import app.antresol.core.model.WaySearchResults
import app.antresol.core.model.WayStateResponse
import app.antresol.core.model.WayStep
import app.antresol.core.model.toCard
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
        "addons-web" to ListSerializer(serializer<AddonRef>()),
        "app-info" to serializer<AppInfo>(),
        "app-run-done" to serializer<RunResult>(),
        "app-run-input" to serializer<RunResult>(),
        "app-run-pick" to serializer<RunResult>(),
        "app-run-play" to serializer<RunResult>(),
        "app-search" to serializer<WaySearchResults>(),
        "app-state" to serializer<WayStateResponse>(),
        "app-step" to serializer<WayStep>(),
        "input-info" to serializer<InputInfo>(),
        "title-kid" to serializer<TitleInfo>(),
        "title-series" to serializer<TitleInfo>(),
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
        "channel-epg" to serializer<ChannelEpg>(),
        "channel-guide" to serializer<ChannelGuide>(),
        "channels" to serializer<ChannelList>(),
        "comic-pages" to serializer<ComicPages>(),
        "error-401" to serializer<ErrorBody>(),
        "health" to serializer<Health>(),
        "hls-keepalive" to serializer<HlsStatus>(),
        "home" to serializer<HomeResponse>(),
        "item-episode" to serializer<ItemDetail>(),
        "item-movie" to serializer<ItemDetail>(),
        "item-season" to serializer<ItemDetail>(),
        "item-series" to serializer<ItemDetail>(),
        "item-trailers" to serializer<TrailerList>(),
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
        "requests-all" to ListSerializer(serializer<KidRequest>()),
        "requests-count" to serializer<RequestCount>(),
        "requests-mine" to ListSerializer(serializer<KidRequest>()),
        "requests-search" to ListSerializer(serializer<KidCandidate>()),
        "search-music" to serializer<SearchResults>(),
        "search" to serializer<SearchResults>(),
        "setup-get" to serializer<SetupStatus>(),
        "tracking-episodes" to serializer<EpisodeGuideResponse>(),
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
    fun addonWaysProtocol() {
        val refs = sample<List<AddonRef>>("addons-web")
        assertEquals(listOf("beta", "alpha"), refs.map { it.id })

        val info = sample<AppInfo>("app-info")
        assertEquals(1, info.v)
        assertEquals("Find and watch", info.search?.label)
        val watch = info.ways[0]
        assertEquals("watch", watch.id)
        assertEquals(WayRole.WATCH, watch.role)
        assertEquals(WayScope.ANY, watch.scope)
        assertEquals(listOf("movie", "series"), watch.kinds)
        assertTrue(watch.episodes)
        val keep = info.ways[1]
        assertTrue(keep.isKeep)
        assertEquals(WayScope.OUTSIDE, keep.scope)
        assertFalse(keep.episodes)
        // The addon id is the app's, never the server's: it is not part of the answer.
        assertEquals("", watch.addonId)

        val step = sample<WayStep>("app-step")
        assertEquals("Source", step.title)
        assertEquals("Pick a source", step.note)
        assertEquals("Nothing found", step.empty)
        assertEquals(listOf("4K", "Dolby Vision"), step.rows[0].badges)
        assertTrue(step.rows[0].current)
        assertFalse(step.rows[0].isFinal)
        assertEquals("Second line", step.rows[0].detail)
        assertTrue(step.rows[1].isFinal)
        assertNull(step.rows[1].detail)

        assertEquals("in-7f3a", sample<RunResult>("app-run-input").input)
        assertEquals("Started", sample<RunResult>("app-run-done").done)
        assertTrue(sample<RunResult>("app-run-pick").pick)
        val play = sample<RunResult>("app-run-play").play!!
        assertTrue(play.isPlaylist)
        assertEquals(120.5, play.startSec, 0.0)
        assertEquals(5_400_000L, play.durationMs)
        assertEquals("sess1", play.session)
        assertEquals(10, play.progress.every)
        assertEquals("1080p", play.quality.current)
        assertEquals("English", play.subtitles.single().label)
        assertEquals("/api/addons/alpha/x/media/sess1/subs/0.vtt", play.subtitles.single().url)

        val state = sample<WayStateResponse>("app-state").ways
        assertTrue(state.getValue("keep").active)
        assertEquals(0.4, state.getValue("keep").fraction!!, 0.0)
        assertEquals("Saved 40%", state.getValue("keep").text)
        assertTrue(state.getValue("keep").cancel)
        assertFalse(state.getValue("watch").active)

        val titles = sample<WaySearchResults>("app-search").titles
        // A title in the library opens its card; one off it, the page of the title by its TMDB id.
        val inLibrary = titles[0].toCard()
        assertEquals(42L, inLibrary.id)
        assertFalse(inLibrary.opensTitlePage)
        val outside = titles[1].toCard()
        assertEquals(0L, outside.id)
        assertEquals(1399L, outside.tmdbId)
        assertTrue(outside.opensTitlePage)

        val input = sample<InputInfo>("input-info")
        assertEquals("alpha", input.addonId)
        assertEquals(12.5, input.startSec, 0.0)
        assertEquals(1399L, input.title?.tmdbId)

        val title = sample<TitleInfo>("title-series")
        assertFalse(title.inLibrary)
        assertNull(title.libraryItemId)
        assertEquals(2, title.seasons.size)
        val item = title.asItem()
        assertEquals(0L, item.id)
        assertEquals(1399L, item.tmdbId)
        assertEquals(57, item.runtime)
        assertEquals("Emilia Clarke", item.cast.single().name)
    }

    @Test
    fun kidRequests() {
        val found = sample<List<KidCandidate>>("requests-search")
        assertEquals(listOf("movie", "series", "music"), found.map { it.kind })
        assertNull(found[0].kidRequest?.request)
        assertEquals("tmdb:603", found[0].kidRequest?.key)
        assertEquals(RequestStatus.PENDING, found[1].kidRequest?.request?.status)
        assertEquals("ext:mb-1", found[2].kidRequest?.key)
        assertEquals(listOf("The Blue Album"), found[2].aliases)

        val mine = sample<List<KidRequest>>("requests-mine")
        assertEquals(listOf(RequestStatus.DECLINED, RequestStatus.IN_LIBRARY, RequestStatus.PENDING), mine.map { it.status })
        assertEquals("Not yet", mine[0].reason)
        assertEquals(42L, mine[1].libraryItemId)
        assertTrue(mine[2].hidden)
        assertFalse(mine[2].hasTitlePage)
        assertTrue(mine[0].hasTitlePage)
        assertNull(mine[0].user)

        val all = sample<List<KidRequest>>("requests-all")
        assertEquals("Sam", all[0].user?.name)
        assertEquals(2, sample<RequestCount>("requests-count").pending)

        // A kid's title page: a title above the age limit comes with the server's reason, and nothing asked yet.
        val state = sample<TitleInfo>("title-kid").kidRequest!!
        assertFalse(state.allowed)
        assertEquals("This is 16+, and this profile allows up to 6+", state.blocked)
        assertNull(state.request)
    }

    @Test
    fun episodeGuide() {
        val guide = sample<EpisodeGuideResponse>("tracking-episodes")
        assertEquals(1399L, guide.title.tmdbId)
        assertEquals(2L, guide.title.itemId)
        assertEquals(5, guide.episodes.size)
        val first = guide.episodes.first()
        assertTrue(first.watched)
        assertEquals(4L, first.librarySource?.meta?.itemId)
        assertEquals(7L, first.librarySource?.meta?.fileId)
        assertEquals(300, guide.episodes[1].positionSec)
        val xSource = guide.episodes[2]
        assertNull(xSource.librarySource)
        assertTrue(xSource.new)
        assertEquals(listOf("LostFilm"), xSource.sources.single().labels)
        assertTrue(guide.episodes[3].sources.isEmpty())
        assertEquals("", guide.episodes[4].airDate)
    }

    @Test
    fun channelsAndGuide() {
        val list = sample<ChannelList>("channels")
        assertTrue(list.channels.size >= 2)
        val first = list.channels.first()
        assertEquals(1L, first.id)
        assertEquals(1, first.number)
        assertTrue(first.name.isNotBlank() && first.category.isNotBlank())
        assertEquals(2L, list.lastChannel)
        assertTrue(list.channels.single { it.id == 3L }.favorite)
        // An uploaded logo (round 5) only where there is one.
        assertEquals("/api/channels/2/logo.png?v=3", list.channels.single { it.id == 2L }.logoUrl)
        assertNull(first.logoUrl)
        val now = requireNotNull(first.now)
        assertEquals("/api/images/local/18/backdrop", now.backdrop)
        assertEquals("1-1", now.id)
        assertEquals("movie", now.kind)
        assertEquals(listOf("комедия", "приключения"), now.genres)
        assertTrue(now.start < now.end)
        assertNotNull(first.next)
        val epg = sample<ChannelEpg>("channel-epg")
        assertTrue(epg.from < epg.to)
        assertEquals(listOf(1L, 2L), epg.channels.map { it.id })
        assertTrue(epg.channels.first().programs.isNotEmpty())
        val guide = sample<ChannelGuide>("channel-guide")
        assertEquals(1L, guide.channel.id)
        assertEquals(4, guide.programs.size)
        assertEquals(guide.programs[0].end, guide.programs[1].start)
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

    @Test
    fun trailers() {
        val list = sample<TrailerList>("item-trailers")
        assertEquals(2, list.trailers.size)
        val first = list.trailers[0]
        assertEquals("abc123XYZ_0", first.key)
        assertEquals("vnd.youtube:abc123XYZ_0", first.appUrl)
        assertEquals("https://www.youtube.com/watch?v=abc123XYZ_0", first.webUrl)
        assertEquals("/api/trailers/abc123XYZ_0/play", first.play)
        assertTrue(first.playsInApp)
        // Older servers and trailers off: no `play`, the apps open the link outside.
        assertEquals("", list.trailers[1].play)
        assertTrue(!list.trailers[1].playsInApp)
        assertEquals("https://www.youtube.com/watch?v=k", Trailer(key = "k").webUrl)
        assertTrue(json.decodeFromString(serializer<TrailerList>(), "{}").trailers.isEmpty())
    }

    @Test
    fun watchedThumbAndCastPhoto() {
        val card = json.decodeFromString(
            serializer<Card>(),
            """{"id":7,"kind":"episode","backdrop":"/b","thumb":"/api/images/frame/7","watched":true}""",
        )
        assertTrue(card.watched)
        assertEquals("/api/images/frame/7", card.wideImage)
        assertEquals("/b", card.copy(thumb = "").wideImage)
        assertEquals("/p", Card(id = 1, poster = "/p").wideImage)
        // Omitted when false / empty.
        val plain = json.decodeFromString(serializer<Card>(), """{"id":8,"kind":"movie"}""")
        assertFalse(plain.watched)
        assertEquals("", plain.thumb)

        val detail = json.decodeFromString(
            serializer<ItemDetail>(),
            """{"id":2,"kind":"series","cast":[{"name":"A B","character":"C","photo":"/api/images/cast/A%20B"},{"name":"D"}],
               "seasons":[{"id":3,"number":1,"episodes":[{"id":4,"thumb":"/api/images/frame/4","watched":true}]}]}""",
        )
        assertEquals("/api/images/cast/A%20B", detail.cast[0].photo)
        assertEquals("", detail.cast[1].photo)
        val episode = detail.seasons[0].episodes[0]
        assertEquals("/api/images/frame/4", episode.thumb)
        assertTrue(episode.toCard().watched)
        assertEquals("/api/images/frame/4", episode.toCard().thumb)
    }

    @Test
    fun homeWithoutBooks() {
        val home = sample<HomeResponse>("home")
        assertTrue(home.shelves.any { shelf -> shelf.cards.any { Kind.isBook(it.kind) } })
        val tv = home.withoutBooks()
        assertTrue(tv.shelves.none { shelf -> shelf.cards.any { Kind.isBook(it.kind) } })
        assertTrue(tv.shelves.none { it.cards.isEmpty() })
        assertTrue(tv.shelves.any { it.id == "recent-movies" })
    }
}
