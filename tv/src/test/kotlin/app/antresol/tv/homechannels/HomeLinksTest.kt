package app.antresol.tv.homechannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deep links of the TV app: what the tiles carry and what the activity accepts. */
class HomeLinksTest {
    private val nonce = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

    private fun parse(uri: String?) = HomeLinks.parse(uri, nonce)

    /** [uri] as one of our tiles would carry it, so that only its own faults are under test. */
    private fun withNonce(uri: String) =
        if (uri.startsWith("antresol")) "$uri${if ('?' in uri) "&" else "?"}n=$nonce" else uri

    @Test
    fun aLinkSurvivesBeingWrittenAndRead() {
        listOf(HomeLink.Play(19, 3), HomeLink.Open("movie", 19, 3), HomeLink.Open("series", 500, 12)).forEach { link ->
            assertEquals(link, parse(HomeLinks.uri(link, nonce)))
        }
    }

    @Test
    fun theAddressesAreWhatTheManifestAndTheDocsSay() {
        assertEquals("antresol://play/19?u=3&n=$nonce", HomeLinks.uri(HomeLink.Play(19, 3), nonce))
        assertEquals("antresol://title/series/500?u=12&n=$nonce", HomeLinks.uri(HomeLink.Open("series", 500, 12), nonce))
        assertEquals("antresol://play/19?u=3", HomeLinks.uri(HomeLink.Play(19, 3)))
    }

    @Test
    fun aLinkWithoutTheInstallsNonceIsIgnored() {
        listOf(
            "antresol://play/19?u=3", // what another app would guess
            "antresol://play/19?u=3&n=", // empty
            "antresol://play/19?u=3&n=${nonce.dropLast(1)}", // a prefix
            "antresol://play/19?u=3&n=${nonce}0", // longer
            "antresol://play/19?u=3&n=${nonce.uppercase()}", // another case
            "antresol://play/19?u=3&n=ffffffffffffffffffffffffffffffff",
            "antresol://title/movie/19?u=3&nn=$nonce",
            "antresol://title/movie/19?u=3&x=n=$nonce",
            "antresol://title/movie/19?n=wrong&u=3&n=$nonce", // the first n counts
        ).forEach { assertNull(it, parse(it)) }
        // In any position, with other parts around it.
        assertEquals(HomeLink.Play(19, 3), parse("antresol://play/19?n=$nonce&u=3"))
        assertEquals(HomeLink.Play(19, 3), parse("antresol://play/19?x=1&u=3&n=$nonce&y=2"))
    }

    @Test
    fun noNonceIsNeverAcceptedNotEvenAnEmptyOne() {
        assertNull(HomeLinks.parse("antresol://play/19?u=3", ""))
        assertNull(HomeLinks.parse("antresol://play/19?u=3&n=", ""))
        assertFalse(HomeLinks.nonceMatches("", ""))
        assertFalse(HomeLinks.nonceMatches("a", null))
        assertTrue(HomeLinks.nonceMatches("abc", "abc"))
        assertFalse(HomeLinks.nonceMatches("abc", "abd"))
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
        ).forEach { assertNull("$it", parse(it?.let(::withNonce))) }
    }

    @Test
    fun otherQueryPartsDoNotMatter() {
        assertEquals(HomeLink.Play(19, 3), parse("antresol://play/19?x=1&u=3&y=2&n=$nonce"))
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
