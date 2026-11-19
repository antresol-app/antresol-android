package app.antresol.tv.homechannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deep links of the TV app: what the tiles carry and what the activity accepts. */
class HomeLinksTest {
    @Test
    fun aLinkSurvivesBeingWrittenAndRead() {
        listOf(HomeLink.Play(19, 3), HomeLink.Open("movie", 19, 3), HomeLink.Open("series", 500, 12)).forEach { link ->
            assertEquals(link, HomeLinks.parse(HomeLinks.uri(link)))
        }
    }

    @Test
    fun theAddressesAreWhatTheManifestAndTheDocsSay() {
        assertEquals("antresol://play/19?u=3", HomeLinks.uri(HomeLink.Play(19, 3)))
        assertEquals("antresol://title/series/500?u=12", HomeLinks.uri(HomeLink.Open("series", 500, 12)))
    }

    @Test
    fun anythingElseIsNotALink() {
        listOf(
            null,
            "",
            "https://example.com/play/19?u=3",
            "antresol://play/19", // no profile
            "antresol://play/19?u=abc",
            "antresol://play/19?u=-1",
            "antresol://play/0?u=3",
            "antresol://play/-5?u=3",
            "antresol://play/abc?u=3",
            "antresol://play/19/20?u=3",
            "antresol://play?u=3",
            "antresol://title/19?u=3", // no kind
            "antresol://title/album/19?u=3", // not a video kind
            "antresol://title/movie/x?u=3",
            "antresol://settings/19?u=3",
            "antresol://play/19 ?u=3", // not a valid URI
        ).forEach { assertNull("$it", HomeLinks.parse(it)) }
    }

    @Test
    fun otherQueryPartsDoNotMatter() {
        assertEquals(HomeLink.Play(19, 3), HomeLinks.parse("antresol://play/19?x=1&u=3&y=2"))
    }

    @Test
    fun aRequestGoesStale() {
        val request = DeepLinkRequest(HomeLink.Play(1, 1), atMs = 10_000)
        assertTrue(request.fresh(10_000))
        assertTrue(request.fresh(10_000 + DeepLinkRequest.MAX_AGE_MS))
        assertFalse(request.fresh(10_001 + DeepLinkRequest.MAX_AGE_MS))
        // A clock that went backwards (a reboot) is not fresh either.
        assertFalse(request.fresh(9_000))
    }
}
