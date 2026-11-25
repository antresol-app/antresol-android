package app.antresol.ui.ways

import app.antresol.core.model.AddonSearch
import app.antresol.core.model.AppInfo
import app.antresol.core.model.RunRequest
import app.antresol.core.model.RunResult
import app.antresol.core.model.StepRequest
import app.antresol.core.model.WayCancelRequest
import app.antresol.core.model.WaySearchResults
import app.antresol.core.model.WayStateResponse
import app.antresol.core.model.WayStep
import app.antresol.core.model.WayTitle
import app.antresol.core.ways.AddonWays
import app.antresol.core.ways.WaysApi
import app.antresol.core.ways.WaysSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AddonTitleSearchTest {
    /** Each addon answers when its gate opens, with the titles put in [found] (none: an empty answer); a gate failed with an error answers with it. */
    private class Addons : WaysApi {
        val gates = HashMap<String, CompletableDeferred<Unit>>()
        val found = HashMap<String, List<WayTitle>>()
        val asked = ArrayList<String>()

        fun gate(addon: String) = gates.getOrPut(addon) { CompletableDeferred() }

        override suspend fun search(addonId: String, query: String): WaySearchResults {
            asked += "$addonId $query"
            gate(addonId).await()
            return WaySearchResults(found[addonId].orEmpty())
        }

        override suspend fun addons(): List<String> = error("not used")
        override suspend fun info(addonId: String): AppInfo = error("not used")
        override suspend fun step(addonId: String, request: StepRequest): WayStep = error("not used")
        override suspend fun run(addonId: String, request: RunRequest): RunResult = error("not used")
        override suspend fun state(addonId: String, kind: String, tmdbId: Long, season: Int?): WayStateResponse = error("not used")
        override suspend fun cancel(addonId: String, request: WayCancelRequest): RunResult = error("not used")
    }

    private fun title(id: Long, name: String) = WayTitle(kind = "movie", tmdbId = id, title = name)

    private val ways = AddonWays(emptyList(), listOf(AddonSearch("alpha", "Alpha"), AddonSearch("beta", "Beta")))
    private val source = object : WaysSource {
        override suspend fun get() = ways
        override fun invalidate() {}
    }

    private fun TestScope.search(api: WaysApi, source: WaysSource?) = AddonTitleSearch(api, source, this)

    @Test
    fun searchingLastsUntilEveryAddonAnsweredAndTheAnswersCameInOrder() = runTest(StandardTestDispatcher()) {
        val api = Addons().apply {
            found["alpha"] = listOf(title(1, "One"))
            found["beta"] = listOf(title(2, "Two"))
        }
        val search = search(api, source)
        search.start("on", debounceMs = 300)
        assertTrue("busy from the moment it is asked for", search.searching)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(api.asked.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("alpha on", "beta on"), api.asked)
        assertTrue(search.searching)
        assertTrue(search.sections.isEmpty())

        // beta first: its section still stands after alpha's, as the addons are ordered.
        api.gate("beta").complete(Unit)
        runCurrent()
        assertEquals(listOf("beta"), search.sections.map { it.addonId })
        assertTrue("alpha is still looking", search.searching)
        api.gate("alpha").complete(Unit)
        runCurrent()
        assertEquals(listOf("alpha", "beta"), search.sections.map { it.addonId })
        assertFalse(search.searching)
    }

    @Test
    fun addonsThatFindNothingEndTheSearchWithoutASection() = runTest(StandardTestDispatcher()) {
        val api = Addons()
        val search = search(api, source)
        search.start("zzz", debounceMs = 0)
        runCurrent()
        assertTrue(search.searching)
        api.gate("alpha").complete(Unit)
        api.gate("beta").complete(Unit)
        runCurrent()
        assertTrue(search.sections.isEmpty())
        assertFalse(search.searching)
    }

    @Test
    fun aNewerSearchReplacesTheRunningOneAndKeepsTheBusyFlagUntilItIsDone() = runTest(StandardTestDispatcher()) {
        val api = Addons().apply {
            found["alpha"] = listOf(title(1, "One"))
            found["beta"] = listOf(title(2, "Two"))
        }
        val search = search(api, source)
        search.start("o", debounceMs = 0)
        runCurrent()
        search.start("on", debounceMs = 0)
        runCurrent()
        // The first search was cancelled: finishing it did not end the busy state of the second.
        assertTrue(search.searching)
        api.gate("alpha").complete(Unit)
        runCurrent()
        assertTrue("beta has not answered the newer search", search.searching)
        api.gate("beta").complete(Unit)
        runCurrent()
        assertFalse(search.searching)
        assertEquals(listOf("alpha o", "beta o", "alpha on", "beta on"), api.asked)
        assertEquals(listOf("alpha", "beta"), search.sections.map { it.addonId })
    }

    @Test
    fun clearingStopsTheSearchAndForgetsWhatWasFound() = runTest(StandardTestDispatcher()) {
        val api = Addons().apply { found["alpha"] = listOf(title(1, "One")) }
        val search = search(api, source)
        search.start("on", debounceMs = 0)
        runCurrent()
        api.gate("alpha").complete(Unit)
        runCurrent()
        assertEquals(1, search.sections.size)
        search.start("one", debounceMs = 0)
        runCurrent()
        search.clear()
        runCurrent()
        assertFalse(search.searching)
        assertTrue(search.sections.isEmpty())
    }

    @Test
    fun withoutAddonsNothingIsSearchedAndNothingIsBusy() = runTest(StandardTestDispatcher()) {
        val api = Addons()
        val search = search(api, null)
        search.start("on", debounceMs = 0)
        runCurrent()
        assertFalse(search.searching)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun aSourceThatCannotAnswerLeavesTheSearchEmptyAndIdle() = runTest(StandardTestDispatcher()) {
        val broken = object : WaysSource {
            override suspend fun get(): AddonWays = throw IOException("offline")
            override fun invalidate() {}
        }
        val search = search(Addons(), broken)
        search.start("on", debounceMs = 0)
        runCurrent()
        assertFalse(search.searching)
        assertTrue(search.sections.isEmpty())
    }
}
