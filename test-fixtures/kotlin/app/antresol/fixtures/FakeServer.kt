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

    /**
     * The `x/app` protocol of one addon ("alpha"): a way to watch (a source and a quality to pick, or a
     * remembered one), a way that keeps a copy on the server, a title search, and a title outside the
     * library. Off by default: the server then has no addons, as a demo server.
     */
    @Volatile var addons: Boolean = false

    /** A remembered choice: the watch way's quick run plays at once instead of opening the choice. */
    @Volatile var wayRemembered: Boolean = false

    /** The keep way is working (its state says so, with a progress ring and a way to cancel). */
    @Volatile var keepActive: Boolean = false

    /** The input the way plays is still being prepared for this many asks (answered 202). */
    @Volatile var inputPendingAsks: Int = 0

    /** The watch way answers `play` (a stream the addon serves itself) instead of an input of the core's player. */
    @Volatile var wayDirect: Boolean = false

    /** The first media link (session "s1") answers 410 every time: it is stale, and a run with `refresh` makes a new one ("s2"). */
    @Volatile var staleLink: Boolean = false

    /** A second way to watch ("watch2", «Second source»): the menu of ways has two rows. */
    @Volatile var secondWay: Boolean = false

    /** The addon's search also finds a series that is not in the library («Far Away Show»). */
    @Volatile var searchSeries: Boolean = false

    /** The addon's title search answers after this many ms (a search that is still looking). */
    @Volatile var addonSearchDelayMs: Long = 0

    /** `GET /api/search` finds nothing in the library. */
    @Volatile var searchLibraryEmpty: Boolean = false

    /** The addon's `run` answers after this many ms (a run that is still waiting). */
    @Volatile var runDelayMs: Long = 0

    /** The stream the addon serves lists two qualities and the `ref` that plays it in either (a choice in the player). */
    @Volatile var wayQualities: Boolean = false

    /** The `x/app` requests the addon got, `METHOD path?query body`. */
    val addonCalls = CopyOnWriteArrayList<String>()

    private val inputAsks = java.util.concurrent.atomic.AtomicInteger(0)

    /** Delay of `GET /api/items/{id}` answers, to land them mid-transition. */
    @Volatile var itemDelayMs: Long = 0

    private val images = ConcurrentHashMap<String, ByteArray>()

    /** The JSON `GET /api/music/tracks/{id}/lyrics` answers for every track; null: the track has none (404). */
    @Volatile var lyricsJson: String? = null

    /** The profile is a kid's: the separate-history writes answer 403, as the server's `noKids`. */
    @Volatile var kid: Boolean = false

    /** Kids' requests are open on the server: a kid's `/api/me` has `canRequest`, an administrator's `requests`. */
    @Volatile var requestsOpen: Boolean = false

    /** A kid's catalogue search (`/api/requests/search`) answers after this many ms. */
    @Volatile var requestSearchDelayMs: Long = 0

    /** The requests the kids made, by `kind|key` (a kid's search, an administrator's list). */
    val kidRequests: MutableMap<String, FakeRequest> = java.util.Collections.synchronizedMap(linkedMapOf())

    private val nextRequest = java.util.concurrent.atomic.AtomicLong(1)

    /** A kid's request as the server keeps it. [status] is `pending`, `approved`, `in_library` or `declined`. */
    data class FakeRequest(
        val id: Long,
        val kind: String,
        val key: String,
        val title: String,
        val tmdbId: Long = 0,
        val extId: String = "",
        val subtitle: String = "",
        val year: Int = 0,
        var status: String = "pending",
        var reason: String = "",
        val createdAt: String = "2026-11-24T10:00:00Z",
    )

    /** Makes a request of the kid as if it had asked (a title already asked for, answered or in the library). */
    fun addRequest(kind: String, tmdbId: Long, title: String, status: String = "pending", reason: String = ""): FakeRequest {
        val key = "tmdb:$tmdbId"
        val request = FakeRequest(nextRequest.getAndIncrement(), kind, key, title, tmdbId = tmdbId, year = 2020, status = status, reason = reason)
        kidRequests["$kind|$key"] = request
        return request
    }

    /**
     * «Семья»: the other profiles' places by item id (`GET /api/items/{id}/family` answers the list under that id, an empty
     * one for a kid or an item without any).
     */
    val family: MutableMap<Long, List<FakeFamilyMember>> = ConcurrentHashMap()

    /** What this profile has in each item (`GET /api/progress/{id}`), as `positionMs to durationMs`; an adopted place lands here. */
    val progress: MutableMap<Long, Pair<Long, Long>> = ConcurrentHashMap()

    /** `POST /api/progress/{id}/adopt` bodies that were taken, as `itemId userId`. */
    val adopted = CopyOnWriteArrayList<String>()

    /** One other profile's place in an item, as the server lists it. [agoMinutes] is how long ago they were there. */
    data class FakeFamilyMember(
        val userId: Long,
        val name: String,
        val itemId: Long,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val finished: Boolean = false,
        val next: Boolean = false,
        val season: Int = 0,
        val episode: Int = 0,
        val ownerName: String = "",
        val avatar: String = "grad-2",
        val done: Int = 0,
        val total: Int = 0,
        val agoMinutes: Long = 120,
    )

    /** The body of `GET /api/me/devices` (the signed-in devices of the profile). */
    @Volatile var devicesJson: String =
        """{"items":[{"id":1,"name":"Pixel 8","model":"Pixel 8","version":"0.9.0","lastSeen":"2026-11-25T10:00:00Z","current":true},""" +
            """{"id":2,"name":"Living room TV","model":"Android TV (BRAVIA)","version":"0.9.0","lastSeen":"2026-11-24T20:00:00Z","current":false}]}"""

    /** «Отдельная история на этом устройстве»: the person's histories (id → name) and the one this device writes into. */
    val histories: MutableMap<Long, String> = java.util.Collections.synchronizedMap(linkedMapOf(20L to "Living Room"))

    @Volatile var deviceHistory: Long? = null

    private val nextHistory = java.util.concurrent.atomic.AtomicLong(21)

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path"
                // Keyed without the `?w=` the apps ask a sized image with.
                if (path.startsWith("/api/images/")) imageAuth[path.substringBefore('?')] = request.getHeader("Authorization").orEmpty()
                if (path.startsWith("/api/me/managed") || path.startsWith("/api/me/devices/")) {
                    return history(request.method.orEmpty(), path, request.body.readUtf8())
                }
                if (path.startsWith("/api/requests")) return requests(request.method.orEmpty(), path, request.body.readUtf8())
                if (request.method == "POST" && path.matches(Regex("/api/progress/\\d+/adopt"))) return adopt(path, request.body.readUtf8())
                // A kid has no addons; the title page is the one thing it gets of them (with its `kidRequest`).
                if (kid && path.startsWith("/api/title/")) return addon(request.method.orEmpty(), path, "")
                if (kid && path == "/api/addons/web") {
                    return MockResponse().setResponseCode(403).setBody("""{"error":"Not available in a kid profile"}""")
                }
                if (addons && (path.startsWith("/api/addons/") || path.startsWith("/api/title/") || path.startsWith("/api/play/input/"))) {
                    return addon(request.method.orEmpty(), path, request.body.readUtf8())
                }
                if (path == "/api/addons/web") return json("[]")
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
            path == "/api/auth/token" -> json("""{"token":"test-device-token","user":${me()}}""")
            path == "/api/me" -> json(me())
            path == "/api/auth/media-key" -> json("""{"key":"test-mk","expiresAt":"2099-01-01T00:00:00Z"}""")
            path == "/api/me/devices" -> json(devicesJson)
            path == "/api/me/ui-language" -> json("""{"language":"","effective":"en"}""")
            path == "/api/me/playback-prefs" -> json("""{"audioLangs":[],"subMode":"off","subLangs":[]}""")
            path == "/api/home" -> json(home())
            path == "/api/search" -> json("""{"library":[${if (searchLibraryEmpty) "" else movieCard(MOVIE_ID)}],"albums":[],"artists":[],"songs":[]}""")
            path == "/api/items" -> json(if ("kind=series" in query) shows() else movies())
            path.matches(Regex("/api/items/\\d+/trailers")) -> json(trailers(path.split('/')[3].toLong()))
            path.matches(Regex("/api/items/\\d+/family")) -> json(familyJson(path.split('/')[3].toLong()))
            path.matches(Regex("/api/items/\\d+")) -> json(item(path.substringAfterLast('/').toLong()))
                .setBodyDelay(itemDelayMs, TimeUnit.MILLISECONDS)
            path.startsWith("/api/progress/") && method == "GET" -> json(progressJson(path.substringAfterLast('/').toLongOrNull() ?: 0))
            path.startsWith("/api/music/home") || path.startsWith("/api/books/home") -> json("""{"shelves":[]}""")
            path == "/api/music/genres" -> json("""{"genres":[]}""")
            path.matches(Regex("/api/music/tracks/\\d+/lyrics")) -> lyricsJson?.let(::json)
                ?: MockResponse().setResponseCode(404).setBody("""{"error":"no lyrics"}""")
            path == "/api/channels" -> json("""{"channels":[]}""")
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
        }
    }

    private fun me(): String = ME.replace(
        """"isKid":false""",
        """"isKid":$kid,"canRequest":${kid && requestsOpen},"requests":${!kid && requestsOpen},"deviceHistory":${historyRef()}""",
    )

    private fun historyRef(): String = deviceHistory?.let { id -> """{"id":$id,"name":"${histories[id]}"}""" } ?: "null"

    private fun progressJson(itemId: Long): String {
        val (position, duration) = progress[itemId] ?: return """{"positionMs":0,"durationMs":0,"finished":false}"""
        val fraction = if (duration > 0) position.toDouble() / duration else 0.0
        return """{"positionMs":$position,"durationMs":$duration,"finished":false,"locator":"","fraction":$fraction,"updatedAt":"2026-11-25T19:00:00Z"}"""
    }

    /** `GET /api/items/{id}/family`: nobody for a kid; else the profiles set in [family] for the item, most recent first. */
    private fun familyJson(itemId: Long): String {
        val members = if (kid) emptyList() else family[itemId].orEmpty().sortedBy { it.agoMinutes }
        return """{"members":[${members.joinToString(",") { m ->
            val at = java.time.Instant.now().minusSeconds(m.agoMinutes * 60).toString()
            val fraction = if (m.durationMs > 0) m.positionMs.toDouble() / m.durationMs else 0.0
            val owner = if (m.ownerName.isEmpty()) "" else ""","ownerName":"${m.ownerName}""""
            val series = if (m.season > 0) ""","season":${m.season},"episode":${m.episode},"title":"Episode ${m.episode}"""" else ""
            val counts = if (m.total > 0) ""","done":${m.done},"total":${m.total}""" else ""
            """{"userId":${m.userId},"name":"${m.name}"$owner,"avatar":"${m.avatar}","isKid":false,"itemId":${m.itemId},"positionMs":${m.positionMs},
                "durationMs":${m.durationMs},"fraction":${if (m.finished) 1.0 else fraction},"finished":${m.finished},"updatedAt":"$at"
                ${if (m.next) ""","next":true""" else ""}$series$counts}"""
        }}]}"""
    }

    /** `POST /api/progress/{id}/adopt`: the other profile's unfinished position in the item becomes this profile's own (404 when there is none). */
    private fun adopt(path: String, body: String): MockResponse {
        val itemId = path.split('/')[3].toLong()
        val userId = Regex(""""userId"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLong() ?: 0L
        val theirs = family.values.flatten().firstOrNull { it.itemId == itemId && it.userId == userId && !it.finished && !it.next && it.positionMs > 0 }
            ?: return MockResponse().setResponseCode(404).setBody("""{"error":"Not found"}""")
        adopted += "$itemId $userId"
        progress[itemId] = theirs.positionMs to theirs.durationMs
        return json(progressJson(itemId))
    }

    /** The separate-history endpoints, as `managed_handlers.go` answers them (no merge bookkeeping: a merge drops the history). */
    private fun history(method: String, path: String, body: String): MockResponse {
        val refused = MockResponse().setResponseCode(403).setBody("""{"error":"Only an administrator can change this"}""")
        return when {
            path == "/api/me/managed" && method == "GET" -> json(
                """{"items":[${histories.entries.joinToString(",") { (id, name) -> """{"id":$id,"name":"$name","avatar":"grad-2","devices":[]}""" }}]}""",
            )
            kid -> refused
            path == "/api/me/managed" && method == "POST" -> {
                val name = Regex(""""name"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1).orEmpty().trim()
                if (name.isEmpty()) return MockResponse().setResponseCode(400).setBody("""{"error":"Enter a name"}""")
                val id = nextHistory.getAndIncrement()
                histories[id] = name
                json("""{"id":$id,"name":"$name","avatar":"grad-4","devices":[]}""").setResponseCode(201)
            }
            path == "/api/me/devices/current/history" && method == "PUT" -> {
                if (body.contains(""""separate":true""")) {
                    deviceHistory = Regex(""""accountId"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLong()?.takeIf { it != 0L }
                        ?: histories.keys.first()
                } else {
                    val was = deviceHistory
                    deviceHistory = null
                    if (was != null && body.contains(""""merge":true""")) histories.remove(was)
                }
                json("""{"id":1,"history":${historyRef()}}""")
            }
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
        }
    }

    /**
     * The kid-request endpoints, as `kid_requests.go` answers them: search (a movie and a series from the catalogue, an
     * album), ask, the kid's own list, and the administrator's list, count, approve and decline.
     */
    private fun requests(method: String, fullPath: String, body: String): MockResponse {
        val path = fullPath.substringBefore('?')
        val query = fullPath.substringAfter('?', "")
        fun param(name: String) = query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=').orEmpty()
        fun bodyOf(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
        fun forbidden(why: String) = MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("""{"error":"$why"}""")
        if (!requestsOpen) return forbidden("Requests are not taken here")
        return when {
            path == "/api/requests/search" && method == "GET" -> {
                if (!kid) return forbidden("Only kid profiles can ask")
                when (param("kind")) {
                    "video" -> json("[${candidates().joinToString(",")}]")
                    "music" -> json("[${candidateJson("music", 0, "Test Album", "Test Artist", "mb-1")}]")
                    else -> json("[]")
                }.setBodyDelay(requestSearchDelayMs, TimeUnit.MILLISECONDS)
            }
            path == "/api/requests/mine" && method == "GET" ->
                json("[${kidRequests.values.sortedByDescending { it.id }.joinToString(",") { requestJson(it, admin = false) }}]")
            path == "/api/requests" && method == "POST" -> {
                if (!kid) return forbidden("Only kid profiles can ask")
                val kind = bodyOf("kind")
                val tmdb = Regex(""""tmdbId"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLong() ?: 0L
                if (tmdb == BLOCKED_TMDB) {
                    return MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json")
                        .setBody("""{"error":"This is 16+, and this profile allows up to 6+","code":"rating"}""")
                }
                val extId = bodyOf("extId")
                val key = if (kind == "movie" || kind == "series") "tmdb:$tmdb" else "ext:$extId"
                val made = kidRequests.getOrPut("$kind|$key") {
                    FakeRequest(nextRequest.getAndIncrement(), kind, key, bodyOf("title"), tmdbId = tmdb, extId = extId, subtitle = bodyOf("subtitle"))
                }
                json(requestJson(made, admin = false))
            }
            path == "/api/requests" && method == "GET" ->
                json("[${kidRequests.values.sortedWith(compareBy({ it.status != "pending" }, { -it.id })).joinToString(",") { requestJson(it, admin = true) }}]")
            path == "/api/requests/count" -> json("""{"pending":${kidRequests.values.count { it.status == "pending" }}}""")
            Regex("/api/requests/\\d+/(approve|decline)").matches(path) -> {
                val id = path.split('/')[3].toLong()
                val found = kidRequests.values.firstOrNull { it.id == id }
                    ?: return MockResponse().setResponseCode(404).setBody("""{"error":"Request not found"}""")
                if (found.status != "pending") return MockResponse().setResponseCode(409).setBody("""{"error":"Already answered"}""")
                if (path.endsWith("/approve")) {
                    found.status = "approved"
                } else {
                    found.status = "declined"
                    found.reason = bodyOf("reason")
                }
                json(requestJson(found, admin = true))
            }
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
        }
    }

    /** The catalogue's answer to a kid's video search: a movie and a series off the library, one the profile may not ask for. */
    private fun candidates(): List<String> = listOf(
        candidateJson("movie", OUTSIDE_TMDB, "Far Away Film"),
        candidateJson("series", OUTSIDE_SERIES_TMDB, "Far Away Show"),
        candidateJson("movie", BLOCKED_TMDB, "Grown-up Film"),
    )

    private fun candidateJson(kind: String, tmdb: Long, title: String, subtitle: String = "", extId: String = ""): String {
        val key = if (kind == "movie" || kind == "series") "tmdb:$tmdb" else "ext:$extId"
        val made = kidRequests["$kind|$key"]
        val poster = if (kind == "movie" || kind == "series") "/api/images/local/outside/poster" else ""
        return """{"kind":"$kind","tmdbId":$tmdb,"extId":"$extId","title":"$title","subtitle":"$subtitle","year":2020,"poster":"$poster","explicit":false,"aliases":[],
            "kidRequest":{"key":"$key","allowed":true,"request":${made?.let { requestJson(it, admin = false) } ?: "null"}}}"""
    }

    private fun requestJson(r: FakeRequest, admin: Boolean): String {
        val video = r.kind == "movie" || r.kind == "series"
        val user = if (admin) ""","user":{"id":2,"name":"Kiddo","avatar":"grad-2"}""" else ""
        return """{"id":${r.id},"key":"${r.key}","kind":"${r.kind}","tmdbId":${r.tmdbId},"extId":"${r.extId}","title":"${r.title}","subtitle":"${r.subtitle}",
            "year":${r.year},"poster":"${if (video) "/api/images/local/outside/poster" else ""}","aliases":[],"rating":"${if (video) "6+" else ""}","status":"${r.status}",
            "reason":"${r.reason}","libraryItemId":null,"createdAt":"${r.createdAt}"$user}"""
    }

    /** The `kidRequest` field of a title page: asked for already (the request), allowed, or above the profile's limit. */
    private fun kidRequestOf(kind: String, tmdb: Long): String {
        val blocked = tmdb == BLOCKED_TMDB
        val made = kidRequests["$kind|tmdb:$tmdb"]?.let { requestJson(it, admin = false) } ?: "null"
        val why = if (blocked) "\"This is 16+, and this profile allows up to 6+\"" else "null"
        return """"kidRequest":{"key":"tmdb:$tmdb","allowed":${!blocked},"blocked":$why,"request":$made}"""
    }

    /** The `x/app` protocol of "alpha", `GET /api/title`, and the core's input player for what the way plays. */
    private fun addon(method: String, fullPath: String, body: String): MockResponse {
        val path = fullPath.substringBefore('?')
        val query = fullPath.substringAfter('?', "")
        if (path.startsWith("/api/addons/alpha/x/")) {
            addonCalls += "$method ${path.removePrefix("/api/addons/alpha/x/")}${if (query.isEmpty()) "" else "?$query"} $body"
        }
        fun bodyOf(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        return when {
            path == "/api/addons/web" -> json("""[{"id":"alpha","entry":"/api/addons/alpha/web/index.js"}]""")
            path == "/api/addons/alpha/x/app" -> json(
                """{"v":1,"ways":[
                    {"id":"watch","label":"Watch from a source","icon":"globe","role":"watch","kinds":["movie","series"],"scope":"any","episodes":true},
                    ${if (secondWay) """{"id":"watch2","label":"Second source","icon":"play","hint":"Plays while it loads","role":"watch","kinds":["movie","series"],"scope":"any","episodes":true},""" else ""}
                    {"id":"keep","label":"Keep on the server","icon":"download","role":"keep","kinds":["movie","series"],"scope":"outside","episodes":false}
                   ],"search":{"label":"Find and watch"}}""",
            )
            path == "/api/addons/alpha/x/app/search" -> json(
                """{"titles":[
                    {"kind":"movie","tmdbId":$MOVIE_TMDB,"title":"Movie 1","year":2019,"poster":"/api/images/local/$MOVIE_ID/poster","inLibrary":true,"libraryItemId":$MOVIE_ID},
                    {"kind":"movie","tmdbId":$OUTSIDE_TMDB,"title":"Far Away Film","year":2020,"poster":"/api/images/local/outside/poster","inLibrary":false}
                    ${if (searchSeries) """,{"kind":"series","tmdbId":$OUTSIDE_SERIES_TMDB,"title":"Far Away Show","year":2021,"poster":"/api/images/local/outside/poster","inLibrary":false}""" else ""}
                   ]}""",
            ).setBodyDelay(addonSearchDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            path == "/api/addons/alpha/x/app/step" -> when (bodyOf("ref")) {
                null -> json(
                    """{"title":"Source","note":"Pick a source","rows":[
                        {"ref":"src-1","label":"Source One","detail":"Fast","badges":["HD","Audio RU EN","Subtitles"],"current":true,"final":false},
                        {"ref":"src-2","label":"Source Two","final":false}]}""",
                )
                "src-1", "src-2" -> json(
                    """{"title":"Quality","rows":[
                        {"ref":"q-1080","label":"1080p","detail":"12.3 GB","badges":["4K"],"final":true},
                        {"ref":"q-720","label":"720p","final":true}]}""",
                )
                else -> json("""{"title":"Nothing","rows":[],"empty":"Nothing found"}""")
            }
            path == "/api/addons/alpha/x/app/run" -> when {
                runDelayMs > 0 && bodyOf("way") != "keep" -> json(if (wayRemembered) """{"input":"in-1"}""" else """{"pick":true}""").setBodyDelay(runDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                bodyOf("way") == "keep" -> json("""{"done":"Started"}""")
                // A link made again (stale, or another quality: `quality` names it) is the second session.
                body.contains("\"refresh\":true") -> json(directPlay("s2", bodyOf("quality")))
                body.contains("\"quick\":true") && !wayRemembered && !wayDirect -> json("""{"pick":true}""")
                wayDirect -> json(directPlay("s1"))
                else -> json("""{"input":"in-1"}""")
            }
            path == "/api/addons/alpha/x/app/progress" && method == "PUT" -> MockResponse().setResponseCode(204)
            path.startsWith("/api/addons/alpha/x/app/play/") && method == "DELETE" -> MockResponse().setResponseCode(204)
            path.matches(Regex("/api/addons/alpha/x/media/s\\d/video\\.mp4")) -> {
                val session = path.substringAfter("media/").substringBefore('/')
                when {
                    "mk=test-mk" !in query -> MockResponse().setResponseCode(401).setBody("""{"error":"no key"}""")
                    session == "s1" && staleLink ->
                        MockResponse().setResponseCode(410).setBody("""{"error":"The link is stale"}""")
                    else -> {
                        val bytes = requireNotNull(FakeServer::class.java.getResourceAsStream("/trailer-sample.mp4")).use { it.readBytes() }
                        MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
                    }
                }
            }
            path.matches(Regex("/api/addons/alpha/x/media/s\\d/subs/0\\.vtt")) -> {
                if ("mk=test-mk" !in query) {
                    MockResponse().setResponseCode(401)
                } else {
                    MockResponse().setHeader("Content-Type", "text/vtt").setBody("WEBVTT\n\n00:00:00.000 --> 00:00:05.000\nHello\n")
                }
            }
            path == "/api/addons/alpha/x/app/state" -> json(
                if (keepActive) {
                    """{"ways":{"keep":{"active":true,"fraction":0.4,"text":"Saved 40%","cancel":true}}}"""
                } else {
                    """{"ways":{}}"""
                },
            )
            path == "/api/addons/alpha/x/app/cancel" -> {
                keepActive = false
                json("""{"done":"Cancelled"}""")
            }
            path.matches(Regex("/api/title/(movie|series)/\\d+")) -> {
                val kind = path.split('/')[3]
                val tmdb = path.substringAfterLast('/').toLong()
                val inLibrary = tmdb == MOVIE_TMDB
                json(
                    """{"tmdbId":$tmdb,"extId":"","kind":"$kind","title":"Far Away ${if (kind == "movie") "Film" else "Show"}","year":2020,
                        "poster":"/api/images/local/outside/poster","overview":"A title the library does not have.","inLibrary":$inLibrary,
                        "libraryItemId":${if (inLibrary) MOVIE_ID.toString() else "null"},"backdrop":"/api/images/local/outside/backdrop",
                        "genres":["Drama"],"runtime":95,"cast":[],"seasons":[{"number":1,"episodes":3}]${if (kid) ",${kidRequestOf(kind, tmdb)}" else ""}}""",
                )
            }
            path == "/api/play/input/in-1" && method == "GET" ->
                json("""{"id":"in-1","addonId":"alpha","name":"Movie.mkv","itemId":0,"slow":false,"startSec":0}""")
            path == "/api/play/input/in-1" && method == "POST" ->
                if (inputAsks.getAndIncrement() < inputPendingAsks) {
                    MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json").setBody("""{"pending":true}""")
                } else {
                    json(
                        """{"mode":"direct","url":"/api/play/input/in-1/direct","startSec":0,"durationMs":30000,"audio":[],"audioIndex":-1,
                            "subtitles":[],"quality":{"selected":"auto","applied":"auto"}}""",
                    )
                }
            path == "/api/play/input/in-1/progress" -> MockResponse().setResponseCode(204)
            path == "/api/play/input/in-1/direct" -> {
                val bytes = requireNotNull(FakeServer::class.java.getResourceAsStream("/trailer-sample.mp4")).use { it.readBytes() }
                MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
            }
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
        }
    }

    /** The `play` answer of a run: an MP4 the addon serves under its session [session], with one subtitle. */
    private fun directPlay(session: String, quality: String? = null): String {
        val options = if (wayQualities) """["1080p","720p"]""" else """["1080p"]"""
        val ref = if (wayQualities) ""","ref":"again-ref"""" else ""
        return """{"play":{"mode":"stream","kind":"mp4","url":"/api/addons/alpha/x/media/$session/video.mp4","startSec":0,"durationMs":30000,"audio":[],"audioIndex":-1,
            "subtitles":[{"index":0,"lang":"en","label":"English","codec":"webvtt","external":true,"url":"/api/addons/alpha/x/media/$session/subs/0.vtt"}],
            "quality":{"options":$options,"current":"${quality ?: "1080p"}"},"session":"$session","progress":{"path":"x/app/progress","every":3}$ref}}"""
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

    // A separate history has its own Continue Watching: what this device watched.
    private val continueCards: String
        get() = if (deviceHistory != null) {
            movieCard(MOVIE_ID + 10, progress = 0.6)
        } else {
            "${episodeCard(EPISODE_IDS[1], 1, 2, progress = 0.4)},${movieCard(MOVIE_ID, progress = 0.3)}"
        }

    /**
     * «Вышло новое» as the server sends it: a followed title in the library is its own card; one off the library is the
     * TMDB title with id 0, so two of them share kind and id (the apps crashed on that repeated key).
     */
    private val trackedCards: String
        get() = listOf(
            seriesCard(),
            """{"id":0,"kind":"series","title":"Far Away Show","subtitle":"S2 · E1","tmdbKind":"series","tmdbId":9001,"season":2,"episode":1}""",
            """{"id":0,"kind":"series","title":"Another Far Show","subtitle":"S1 · E3","tmdbKind":"series","tmdbId":9002,"season":1,"episode":3}""",
            """{"id":0,"kind":"movie","title":"Far Away Film","tmdbKind":"movie","tmdbId":9003}""",
        ).joinToString(",")

    private fun home(): String = """
        {"hero":[${seriesCard()}],
         "shelves":[
           {"id":"continue","title":"Continue Watching","cards":[$continueCards]},
           {"id":"tracked-new","title":"New Releases","cards":[$trackedCards]},
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
             ${if (addons) """"tmdbId":$SERIES_TMDB,""" else ""}
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
             ${if (addons) """"tmdbId":${MOVIE_TMDB + (id - MOVIE_ID)},""" else ""}
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
        /** TMDB ids: the library movie 100 (its neighbours follow by offset), the series, and a title off the library. */
        const val MOVIE_TMDB = 603L
        const val SERIES_TMDB = 1399L
        const val OUTSIDE_TMDB = 9001L

        /** A series the addon's search finds that is not in the library. */
        const val OUTSIDE_SERIES_TMDB = 9002L

        /** A movie a kid profile may not ask for (above its age limit). */
        const val BLOCKED_TMDB = 9009L
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
