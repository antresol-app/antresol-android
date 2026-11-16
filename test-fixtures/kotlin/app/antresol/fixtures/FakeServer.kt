package app.antresol.fixtures

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A MockWebServer that plays a small Antresol library for the shell UI tests: one passwordless
 * profile, home shelves (Continue Watching with an episode), movie and series grids, a movie and a
 * series detail with cast photos, episode thumbs, watched marks and trailers (played by the server:
 * `POST /api/trailers/{key}/play` and a small MP4 from the test resources). Every image path
 * answers a generated PNG in its own colour with a short label, so distinct pictures are visible
 * in the screenshots.
 */
class FakeServer {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<String>()

    /** The Authorization header each image path was requested with. */
    val imageAuth = ConcurrentHashMap<String, String>()

    /** Answers `/api/images/frame/...` with 503 + Retry-After while true (the server busy making frames). */
    @Volatile var framesBusy: Boolean = false

    /** How `POST /api/trailers/{key}/play` answers. */
    enum class TrailerPlay { Ready, BusyOnce, Failed }

    @Volatile var trailerPlay: TrailerPlay = TrailerPlay.Ready

    /** Delay of the trailer play answer, to catch the loading state. */
    @Volatile var trailerPlayDelayMs: Long = 0

    private val busyAnswered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Delay of `GET /api/items/{id}` answers, to land them mid-transition. */
    @Volatile var itemDelayMs: Long = 0

    private val images = ConcurrentHashMap<String, ByteArray>()

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path"
                // Keyed without the `?w=` the apps ask a sized image with.
                if (path.startsWith("/api/images/")) imageAuth[path.substringBefore('?')] = request.getHeader("Authorization").orEmpty()
                return answer(request.method.orEmpty(), path)
            }
        }
        server.start()
    }

    fun shutdown() = server.shutdown()

    private fun answer(method: String, fullPath: String): MockResponse {
        val path = fullPath.substringBefore('?')
        val query = fullPath.substringAfter('?', "")
        if (path.startsWith("/api/images/frame/") && framesBusy) {
            return MockResponse().setResponseCode(503).setHeader("Retry-After", "5").setBody("""{"error":"busy"}""")
        }
        if (path.startsWith("/api/images/")) return image(path)
        if (path.startsWith("/api/trailers/")) return trailer(method, path, query)
        return when {
            path == "/api/health" -> json("""{"ok":true,"serverId":"$SERVER_ID","version":"1.5.0"}""")
            path == "/api/setup" -> json("""{"needsSetup":false}""")
            path == "/api/profiles" -> json("""[{"id":1,"name":"Alex","avatar":"grad-3","hasPassword":false}]""")
            path == "/api/auth/token" -> json("""{"token":"test-device-token","user":$ME}""")
            path == "/api/me" -> json(ME)
            path == "/api/me/ui-language" -> json("""{"language":"","effective":"en"}""")
            path == "/api/me/playback-prefs" -> json("""{"audioLangs":[],"subMode":"off","subLangs":[]}""")
            path == "/api/home" -> json(home())
            path == "/api/items" -> json(if ("kind=series" in query) shows() else movies())
            path.matches(Regex("/api/items/\\d+/trailers")) -> json(trailers(path.split('/')[3].toLong()))
            path.matches(Regex("/api/items/\\d+")) -> json(item(path.substringAfterLast('/').toLong()))
                .setBodyDelay(itemDelayMs, TimeUnit.MILLISECONDS)
            path.startsWith("/api/progress/") && method == "GET" -> json("""{"positionMs":0,"durationMs":0,"finished":false}""")
            path.startsWith("/api/music/home") || path.startsWith("/api/books/home") -> json("""{"shelves":[]}""")
            path == "/api/music/genres" -> json("""{"genres":[]}""")
            path == "/api/channels" -> json("""{"channels":[]}""")
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
        }
    }

    /** `POST /api/trailers/{key}/play` and the MP4 it points at (a 30 s 160x90 clip, `mk` required). */
    private fun trailer(method: String, path: String, query: String): MockResponse {
        val play = Regex("/api/trailers/([A-Za-z0-9_-]+)/play").matchEntire(path)
        if (play != null && method == "POST") {
            val key = play.groupValues[1]
            if (key !in PLAYABLE_TRAILERS) return MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
            return when (trailerPlay) {
                TrailerPlay.Failed -> MockResponse().setResponseCode(502).setBody("""{"error":"Could not fetch the trailer."}""")
                TrailerPlay.BusyOnce -> if (busyAnswered.compareAndSet(false, true)) {
                    MockResponse().setResponseCode(503).setHeader("Retry-After", "1").setBody("""{"error":"busy"}""")
                } else {
                    json("""{"url":"/api/trailers/$key.mp4?mk=test-mk","durationMs":30000}""")
                }
                TrailerPlay.Ready -> json("""{"url":"/api/trailers/$key.mp4?mk=test-mk","durationMs":30000}""")
            }.setBodyDelay(trailerPlayDelayMs, TimeUnit.MILLISECONDS)
        }
        if (path.endsWith(".mp4") && "mk=test-mk" in query) {
            val bytes = requireNotNull(FakeServer::class.java.getResourceAsStream("/trailer-sample.mp4")).use { it.readBytes() }
            return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
        }
        return MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun image(path: String): MockResponse {
        val bytes = images.getOrPut(path) { render(path) }
        return MockResponse()
            .setHeader("Content-Type", "image/png")
            .setHeader("Cache-Control", "private, max-age=604800")
            .setBody(Buffer().write(bytes))
    }

    /** A PNG in a colour of its own with the last path segment as a label. */
    private fun render(path: String): ByteArray {
        val portrait = path.endsWith("/poster") || path.contains("/cast/")
        val (w, h) = if (portrait) 200 to 300 else 480 to 270
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = ((path.hashCode().toLong() * 2654435761L) ushr 8).mod(360L).toFloat()
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.62f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h / 5f
            textAlign = Paint.Align.CENTER
        }
        val label = java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8").take(10)
        canvas.drawText(label, w / 2f, h / 2f + paint.textSize / 3, paint)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private fun home(): String = """
        {"hero":[${seriesCard()}],
         "shelves":[
           {"id":"continue","title":"Continue Watching","cards":[
             ${episodeCard(EPISODE_IDS[1], 1, 2, progress = 0.4)},
             ${movieCard(MOVIE_ID, progress = 0.3)}
           ]},
           {"id":"recent-series","title":"New Series","cards":[${seriesCard()}]},
           {"id":"recent-movies","title":"New Movies","cards":[${(0 until 6).joinToString(",") { movieCard(MOVIE_ID + it, watched = it % 3 == 1) }}]}
         ]}
    """.trimIndent()

    private fun movies(): String =
        """{"items":[${(0 until MOVIE_COUNT).joinToString(",") { movieCard(MOVIE_ID + it, watched = it % 4 == 1) }}],"total":$MOVIE_COUNT}"""

    private fun shows(): String =
        """{"items":[${(0 until SHOW_COUNT).joinToString(",") { i -> if (i == 0) seriesCard() else seriesCard(SERIES_ID + 100 + i, "Show ${i + 1}", watched = i % 5 == 2) }}],"total":$SHOW_COUNT}"""

    private fun movieCard(id: Long, progress: Double = 0.0, watched: Boolean = false) =
        """{"id":$id,"kind":"movie","title":"Movie ${id - MOVIE_ID + 1}","year":${2000 + (id % 20)},
            "poster":"/api/images/local/$id/poster","backdrop":"/api/images/local/$id/backdrop","progress":$progress,
            "badges":["1080p"]${if (watched) ""","watched":true""" else ""}}"""

    private fun seriesCard(id: Long = SERIES_ID, title: String = "The Test Show", watched: Boolean = false) =
        """{"id":$id,"kind":"series","title":"$title","year":2021,"poster":"/api/images/local/$id/poster",
            "backdrop":"/api/images/local/$id/backdrop"${if (watched) ""","watched":true""" else ""}}"""

    private fun episodeCard(id: Long, season: Int, number: Int, progress: Double = 0.0, watched: Boolean = false, guide: Boolean = false) =
        """{"id":$id,"kind":"episode","title":"Episode $number","subtitle":"The Test Show · S$season:E$number",
            "poster":"/api/images/local/$SERIES_ID/poster","backdrop":"/api/images/local/$SERIES_ID/backdrop",
            "thumb":"/api/images/frame/$id","progress":$progress,"seriesId":$SERIES_ID,
            ${if (guide) """"seasonNo":$season,"episodeNo":$number,"durationMs":2700000,"overview":"What happens in episode $number.",""" else """"season":$season,"episode":$number,"""}
            "watched":$watched}"""

    private fun item(id: Long): String = when (id) {
        SERIES_ID -> """
            {"id":$SERIES_ID,"kind":"series","title":"The Test Show","year":2021,"poster":"/api/images/local/$SERIES_ID/poster",
             "backdrop":"/api/images/local/$SERIES_ID/backdrop","overview":"A test series about testing things on a television.",
             "genres":["Drama","Comedy"],"rating":"16+","cast":$CAST,
             "seasons":[
               {"id":${SERIES_ID + 1},"number":1,"title":"Season 1","episodes":[
                 ${EPISODE_IDS.take(4).mapIndexed { i, e -> episodeCard(e, 1, i + 1, progress = if (i == 1) 0.4 else 0.0, watched = i == 0, guide = true) }.joinToString(",")}
               ]},
               {"id":${SERIES_ID + 2},"number":2,"title":"Season 2","episodes":[
                 ${EPISODE_IDS.drop(4).mapIndexed { i, e -> episodeCard(e, 2, i + 1, guide = true) }.joinToString(",")}
               ]}
             ],
             "next":${episodeCard(EPISODE_IDS[1], 1, 2, progress = 0.4, guide = true)}}
        """.trimIndent()
        in EPISODE_IDS -> """{"id":$id,"kind":"episode","title":"Episode","seriesId":$SERIES_ID,"files":[{"id":${id + 1000},"durationMs":2700000}]}"""
        else -> """
            {"id":$id,"kind":"movie","title":"Movie ${id - MOVIE_ID + 1}","year":2019,"poster":"/api/images/local/$id/poster",
             "backdrop":"/api/images/local/$id/backdrop","overview":"A test movie.","genres":["Adventure"],"runtime":104,
             "cast":$CAST,"files":[{"id":${id + 1000},"durationMs":6240000,"audio":[],"subs":[]}],"actions":{"play":true}}
        """.trimIndent()
    }

    private fun trailers(id: Long): String = when (id) {
        MOVIE_ID -> """{"trailers":[
            {"name":"Official Trailer","site":"YouTube","key":"movieTrailer1","type":"Trailer","lang":"en","official":true,"url":"https://www.youtube.com/watch?v=movieTrailer1","play":"/api/trailers/movieTrailer1/play"},
            {"name":"Teaser","site":"YouTube","key":"movieTeaser2","type":"Teaser","lang":"en","official":true,"url":"https://www.youtube.com/watch?v=movieTeaser2"}]}"""
        SERIES_ID -> """{"trailers":[{"name":"Season 1 Trailer","site":"YouTube","key":"seriesTrailer","type":"Trailer","lang":"en","official":true,"url":"https://www.youtube.com/watch?v=seriesTrailer","play":"/api/trailers/seriesTrailer/play"}]}"""
        else -> """{"trailers":[]}"""
    }

    companion object {
        const val SERVER_ID = "fedcba9876543210fedcba9876543210"
        const val SERIES_ID = 500L
        const val MOVIE_ID = 100L
        const val MOVIE_COUNT = 23
        const val SHOW_COUNT = 30
        val EPISODE_IDS = (601L..606L).toList()

        /** Trailers listed with `play`; the movie's teaser has none (an older server: embedded player). */
        val PLAYABLE_TRAILERS = setOf("movieTrailer1", "seriesTrailer")
        private const val ME = """{"id":1,"name":"Alex","avatar":"grad-3","hasPassword":false,"isAdmin":true,"isKid":false}"""
        private val CAST = """[
            {"name":"Ada Lovelace","character":"The Countess","photo":"/api/images/cast/Ada%20Lovelace"},
            {"name":"Alan Turing","character":"The Mathematician","photo":"/api/images/cast/Alan%20Turing"},
            {"name":"Grace Hopper","character":"The Admiral"},
            {"name":"Edsger Dijkstra","character":"The Professor","photo":"/api/images/cast/Edsger%20Dijkstra"},
            {"name":"Barbara Liskov","character":"The Engineer","photo":"/api/images/cast/Barbara%20Liskov"},
            {"name":"Donald Knuth","character":"The Author","photo":"/api/images/cast/Donald%20Knuth"}
        ]""".trimIndent()
    }
}
