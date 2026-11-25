package app.antresol.core.requests

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.KidAsk
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest

/** The server calls of «Попросить»; [AntresolClient.requestsApi] is the real one. */
interface RequestsApi {
    /** What a kid may ask for, of one [app.antresol.core.model.RequestSearchKind]. */
    suspend fun search(query: String, kind: String): List<KidCandidate>

    suspend fun ask(ask: KidAsk): KidRequest

    /** The profile's own requests, newest first. */
    suspend fun mine(): List<KidRequest>

    /** Admins: the pending requests, then those answered lately. */
    suspend fun all(): List<KidRequest>

    /** Admins: how many requests wait for an answer. */
    suspend fun pending(): Int

    suspend fun approve(id: Long): KidRequest

    suspend fun decline(id: Long, reason: String): KidRequest
}

fun AntresolClient.requestsApi(): RequestsApi = object : RequestsApi {
    private val client = this@requestsApi

    override suspend fun search(query: String, kind: String) = client.requestSearch(query, kind)
    override suspend fun ask(ask: KidAsk) = client.askRequest(ask)
    override suspend fun mine() = client.myRequests()
    override suspend fun all() = client.allRequests()
    override suspend fun pending() = client.requestCount().pending
    override suspend fun approve(id: Long) = client.approveRequest(id)
    override suspend fun decline(id: Long, reason: String) = client.declineRequest(id, reason)
}
