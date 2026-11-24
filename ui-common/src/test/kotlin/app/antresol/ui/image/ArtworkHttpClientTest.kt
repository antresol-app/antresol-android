package app.antresol.ui.image

import app.antresol.core.api.AntresolClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** Artwork is downloaded with the API client's timeouts, not OkHttp's shorter defaults. */
class ArtworkHttpClientTest {
    @Test
    fun artworkWaitsAsLongAsTheApiDoes() {
        val api = AntresolClient.defaultHttpClient()
        val artwork = AntresolImageLoader.artworkHttpClient({ "http://server.test" }, { null }, { "en" })
        assertEquals(api.readTimeoutMillis, artwork.readTimeoutMillis)
        assertEquals(30_000, artwork.readTimeoutMillis)
        assertEquals(api.connectTimeoutMillis, artwork.connectTimeoutMillis)
    }
}
