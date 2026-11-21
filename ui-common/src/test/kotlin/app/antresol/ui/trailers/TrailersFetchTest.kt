package app.antresol.ui.trailers

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Kind
import app.antresol.core.model.Trailer
import app.antresol.core.model.TrailerList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trailers a page offers: the best few, as the web's «Ещё» menu lists them. */
class TrailersFetchTest {
    private val asked = ArrayList<String>()

    private fun client(code: Int = 200, body: String): AntresolClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            asked += chain.request().url.encodedPath
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("answer")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return AntresolClient("http://server.test", httpClient = http)
    }

    private fun trailer(key: String, play: Boolean = false) = Trailer(
        name = "T $key",
        site = "YouTube",
        key = key,
        type = "Trailer",
        lang = "en",
        official = true,
        url = "https://www.youtube.com/watch?v=$key",
        play = if (play) "/api/trailers/$key/play" else "",
    )

    private fun answer(vararg trailers: Trailer): String = AntresolClient.json.encodeToString(TrailerList.serializer(), TrailerList(trailers.toList()))

    @Test
    fun theBestThreeInTheServersOrderWithoutThoseThatHaveNoKey() = runTest {
        val body = answer(*listOf("a", "", "b", "c", "d", "e").map { trailer(it, play = it == "a") }.toTypedArray())
        val trailers = Trailers.fetch(client(body = body), id = 7)
        assertEquals(listOf("a", "b", "c"), trailers.map { it.key })
        assertEquals(Trailers.MAX_TRAILERS, trailers.size)
        // One that the server fetches plays in the app's own player, one without `play` in the embedded player.
        assertTrue(trailers.first().playsInApp)
        assertTrue(!trailers.last().playsInApp)
        assertEquals(listOf("/api/items/7/trailers"), asked)
    }

    @Test
    fun aFailureIsNoTrailersAndAKindWithoutTrailersAsksNothing() = runTest {
        assertEquals(emptyList<Any>(), Trailers.fetch(client(code = 500, body = """{"error":"x"}"""), id = 7))
        asked.clear()
        assertEquals(emptyList<Any>(), Trailers.fetch(client(body = answer()), id = 8, kind = Kind.ALBUM))
        assertTrue(asked.isEmpty())
    }
}
