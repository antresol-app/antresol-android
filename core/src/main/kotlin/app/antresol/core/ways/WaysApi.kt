package app.antresol.core.ways

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AddonRef
import app.antresol.core.model.AppInfo
import app.antresol.core.model.RunRequest
import app.antresol.core.model.RunResult
import app.antresol.core.model.StepRequest
import app.antresol.core.model.WayCancelRequest
import app.antresol.core.model.WaySearchResults
import app.antresol.core.model.WayStateResponse
import app.antresol.core.model.WayStep

/** The server calls of the addon ways (the `x/app` protocol); [AntresolClient.waysApi] is the real one. */
interface WaysApi {
    /** The ids of the addons open to the profile. */
    suspend fun addons(): List<String>

    suspend fun info(addonId: String): AppInfo
    suspend fun search(addonId: String, query: String): WaySearchResults
    suspend fun step(addonId: String, request: StepRequest): WayStep
    suspend fun run(addonId: String, request: RunRequest): RunResult
    suspend fun state(addonId: String, kind: String, tmdbId: Long, season: Int? = null): WayStateResponse
    suspend fun cancel(addonId: String, request: WayCancelRequest): RunResult
}

fun AntresolClient.waysApi(): WaysApi = object : WaysApi {
    private val client = this@waysApi

    override suspend fun addons() = client.addonsWeb().map { it.id }.filter { it.isNotBlank() }
    override suspend fun info(addonId: String) = client.appInfo(addonId)
    override suspend fun search(addonId: String, query: String) = client.waySearch(addonId, query)
    override suspend fun step(addonId: String, request: StepRequest) = client.wayStep(addonId, request)
    override suspend fun run(addonId: String, request: RunRequest) = client.wayRun(addonId, request)
    override suspend fun state(addonId: String, kind: String, tmdbId: Long, season: Int?) =
        client.wayState(addonId, kind, tmdbId, season)

    override suspend fun cancel(addonId: String, request: WayCancelRequest) = client.wayCancel(addonId, request)
}
