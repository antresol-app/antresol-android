package app.antresol.core.ways

import app.antresol.core.model.AddonWay
import app.antresol.core.model.AppInfo
import app.antresol.core.model.RunRequest
import app.antresol.core.model.RunResult
import app.antresol.core.model.StepRequest
import app.antresol.core.model.WayCancelRequest
import app.antresol.core.model.WaySearch
import app.antresol.core.model.WaySearchResults
import app.antresol.core.model.WayState
import app.antresol.core.model.WayStateResponse
import app.antresol.core.model.WayStep
import java.util.concurrent.CopyOnWriteArrayList

/** A scripted [WaysApi]: neutral addon ids "alpha" and "beta", every call recorded. */
class FakeWaysApi : WaysApi {
    var addonIds: List<String> = emptyList()
    var addonsFailure: Exception? = null
    val infos = HashMap<String, AppInfo>()
    val infoFailures = HashMap<String, Exception>()
    val searchResults = HashMap<String, WaySearchResults>()
    var steps: (StepRequest) -> WayStep = { WayStep(title = "step") }
    var runs: (RunRequest) -> RunResult = { RunResult(done = "ok") }
    var states: (String) -> Map<String, WayState> = { emptyMap() }
    var cancelResult = RunResult(done = "cancelled")
    var stepFailure: Exception? = null
    var runFailure: Exception? = null
    var stateFailure: Exception? = null

    val calls = CopyOnWriteArrayList<String>()
    val stepRequests = CopyOnWriteArrayList<StepRequest>()
    val runRequests = CopyOnWriteArrayList<RunRequest>()

    override suspend fun addons(): List<String> {
        calls += "addons"
        addonsFailure?.let { throw it }
        return addonIds
    }

    override suspend fun info(addonId: String): AppInfo {
        calls += "info $addonId"
        infoFailures[addonId]?.let { throw it }
        return infos[addonId] ?: throw app.antresol.core.api.ApiException(404, "not found")
    }

    override suspend fun search(addonId: String, query: String): WaySearchResults {
        calls += "search $addonId $query"
        return searchResults[addonId] ?: throw app.antresol.core.api.ApiException(404, "not found")
    }

    override suspend fun step(addonId: String, request: StepRequest): WayStep {
        calls += "step $addonId"
        stepRequests += request
        stepFailure?.let { throw it }
        return steps(request)
    }

    override suspend fun run(addonId: String, request: RunRequest): RunResult {
        calls += "run $addonId"
        runRequests += request
        runFailure?.let { throw it }
        return runs(request)
    }

    override suspend fun state(addonId: String, kind: String, tmdbId: Long, season: Int?): WayStateResponse {
        calls += "state $addonId"
        stateFailure?.let { throw it }
        return WayStateResponse(states(addonId))
    }

    override suspend fun cancel(addonId: String, request: WayCancelRequest): RunResult {
        calls += "cancel $addonId ${request.way}"
        return cancelResult
    }
}

/** A [WaysSource] over fixed ways that counts how often it was reset. */
class FakeSource(var ways: AddonWays) : WaysSource {
    var invalidated = 0

    override suspend fun get(): AddonWays = ways

    override fun invalidate() {
        invalidated++
    }
}

fun way(
    id: String,
    addon: String = "alpha",
    role: String = "watch",
    scope: String = "any",
    kinds: List<String> = listOf("movie", "series"),
    episodes: Boolean = false,
    label: String = id,
) = AddonWay(id = id, label = label, role = role, scope = scope, kinds = kinds, episodes = episodes, addonId = addon)

fun info(vararg ways: AddonWay, search: String? = null) = AppInfo(ways = ways.toList(), search = search?.let { WaySearch(it) })
