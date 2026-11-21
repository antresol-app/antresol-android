package app.antresol.core.ways

import app.antresol.core.api.ApiException
import app.antresol.core.model.WayScope
import app.antresol.core.model.WaySearchResults
import app.antresol.core.model.WayTitle
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AddonWaysTest {
    private val outside = WayTarget("movie", 603, inLibrary = false)
    private val inside = WayTarget("movie", 603, inLibrary = true)

    @Test
    fun theWaysOfAllAddonsInTheOrderOfTheirIdsAndTheirOwnOrder() = runTest {
        val api = FakeWaysApi().apply {
            addonIds = listOf("beta", "alpha")
            infos["alpha"] = info(way("a1"), way("a2"), search = "Find")
            infos["beta"] = info(way("b1", addon = "beta"), search = "Look")
        }
        val found = AddonWays.discover(api)
        assertTrue(found.complete)
        assertEquals(listOf("alpha/a1", "alpha/a2", "beta/b1"), found.ways.ways.map { it.key })
        assertEquals(listOf("alpha" to "Find", "beta" to "Look"), found.ways.searches.map { it.addonId to it.label })
    }

    @Test
    fun anAddonWithoutAPartForTheAppsOrABrokenOneIsLeftOutQuietly() = runTest {
        val api = FakeWaysApi().apply {
            addonIds = listOf("alpha", "beta", "gamma", "delta")
            infos["alpha"] = info(way("a1"))
            // beta: 404 (no `x/app`); gamma: a server error; delta: an unreadable answer
            infoFailures["gamma"] = ApiException(500, "boom")
            infoFailures["delta"] = kotlinx.serialization.SerializationException("bad json")
        }
        val found = AddonWays.discover(api)
        assertEquals(listOf("alpha/a1"), found.ways.ways.map { it.key })
        assertTrue("answers from the server are final", found.complete)
    }

    @Test
    fun aKidProfileOrAServerWithoutAddonsHasNone() = runTest {
        val kid = FakeWaysApi().apply { addonsFailure = ApiException(403, "no") }
        assertSame(AddonWays.NONE, AddonWays.discover(kid).ways)
        assertTrue(AddonWays.discover(kid).complete)
        assertTrue(AddonWays.discover(FakeWaysApi()).ways.isEmpty)
    }

    @Test
    fun aNetworkFailureIsNotKeptAsAnswer() = runTest {
        val api = FakeWaysApi().apply { addonsFailure = IOException("down") }
        assertFalse(AddonWays.discover(api).complete)
        val partly = FakeWaysApi().apply {
            addonIds = listOf("alpha", "beta")
            infos["alpha"] = info(way("a1"))
            infoFailures["beta"] = IOException("timeout")
        }
        val found = AddonWays.discover(partly)
        assertFalse(found.complete)
        assertEquals(listOf("alpha/a1"), found.ways.ways.map { it.key })
    }

    @Test
    fun kindAndScopeDecideWhatATitleOffers() {
        val ways = AddonWays(
            listOf(
                way("any"),
                way("out", scope = WayScope.OUTSIDE),
                way("in", scope = WayScope.INSIDE),
                way("series-only", kinds = listOf("series")),
                way("keep", role = "keep", scope = WayScope.OUTSIDE),
                way("odd-role", role = "other"),
                way("odd-scope", scope = "elsewhere"),
            ),
            emptyList(),
        )
        assertEquals(listOf("any", "out", "keep"), ways.forTarget(outside).map { it.id })
        assertEquals(listOf("any", "in"), ways.forTarget(inside).map { it.id })
        assertEquals(listOf("any", "in", "series-only"), ways.forTarget(WayTarget("series", 1, true)).map { it.id })
        assertEquals(listOf("any", "out"), ways.watchWays(outside).map { it.id })
        assertEquals(listOf("keep"), ways.keepWays(outside).map { it.id })
    }

    @Test
    fun anEpisodeOffersOnlyTheWaysThatRunForOneAndItsOwnScope() {
        val ways = AddonWays(
            listOf(
                way("whole"),
                way("ep", episodes = true),
                way("ep-out", episodes = true, scope = WayScope.OUTSIDE),
                way("ep-in", episodes = true, scope = WayScope.INSIDE),
            ),
            emptyList(),
        )
        // A series in the library: its downloaded episodes are inside, a missing one outside.
        val series = WayTarget("series", 1399, inLibrary = true)
        assertEquals(listOf("ep", "ep-in"), ways.forTarget(series, episode = true, episodeInLibrary = true).map { it.id })
        assertEquals(listOf("ep", "ep-out"), ways.forTarget(series, episode = true, episodeInLibrary = false).map { it.id })
    }

    @Test
    fun searchEmitsTheSectionsAsTheAddonsAnswerInTheOrderOfTheirIds() = runTest {
        val api = FakeWaysApi().apply {
            addonIds = listOf("alpha", "beta", "gamma")
            infos["alpha"] = info(way("a"), search = "From alpha")
            infos["beta"] = info(way("b", addon = "beta"), search = "From beta")
            infos["gamma"] = info(way("c", addon = "gamma"), search = "From gamma")
            searchResults["alpha"] = WaySearchResults(listOf(WayTitle("movie", 1, "One"), WayTitle("movie", 0, "No id")))
            searchResults["beta"] = WaySearchResults(emptyList())
            // gamma: 404, as an addon that went away
        }
        val ways = AddonWays.discover(api).ways
        val last = ways.search(api, "one").toList().last()
        assertEquals(listOf("From alpha"), last.map { it.label })
        assertEquals(listOf("One"), last.single().titles.map { it.title })
        assertTrue(api.calls.contains("search beta one"))
    }
}
