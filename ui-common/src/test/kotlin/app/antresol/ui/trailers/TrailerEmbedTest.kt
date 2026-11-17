package app.antresol.ui.trailers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerEmbedTest {
    /** The embedded player's page says which app it is: `https://` + the app's id in lower case, never the site itself. */
    @Test
    fun thePageIsLoadedUnderTheAppsIdentity() {
        assertEquals("https://com.example.myapp", TrailerEmbed.origin("com.Example.MyApp"))
        val origin = TrailerEmbed.origin("app.antresol.mobile")
        val page = TrailerEmbed.html("movieTeaser2", origin)
        assertTrue(page.contains("origin: 'https://app.antresol.mobile'"))
        assertTrue(page.contains("<script src=\"https://www.youtube.com/iframe_api\"></script>"))
    }
}
