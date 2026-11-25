package app.antresol.ui.requests

import app.antresol.core.api.ApiException
import app.antresol.core.model.KidAsk
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.core.requests.RequestsApi
import kotlinx.coroutines.CompletableDeferred

/** A scripted [RequestsApi]: every call recorded; a call waits for its gate when one is set. */
class FakeRequestsApi : RequestsApi {
    val calls = ArrayList<String>()

    /** The candidates each search kind answers. */
    val found = HashMap<String, List<KidCandidate>>()
    val searchGates = HashMap<String, CompletableDeferred<Unit>>()
    val searchFailures = HashMap<String, Exception>()
    var askFailure: Exception? = null
    var askGate: CompletableDeferred<Unit>? = null
    var mineList: List<KidRequest> = emptyList()
    var mineFailure: Exception? = null
    var allList: List<KidRequest> = emptyList()
    var pendingCount = 0
    var pendingFailure: Exception? = null
    var answerFailure: Exception? = null

    fun gate(kind: String) = searchGates.getOrPut(kind) { CompletableDeferred() }

    override suspend fun search(query: String, kind: String): List<KidCandidate> {
        calls += "search $kind $query"
        searchGates[kind]?.await()
        searchFailures[kind]?.let { throw it }
        return found[kind].orEmpty()
    }

    override suspend fun ask(ask: KidAsk): KidRequest {
        calls += "ask ${ask.kind} ${ask.tmdbId.takeIf { it > 0 } ?: ask.extId}"
        askGate?.await()
        askFailure?.let { throw it }
        return KidRequest(id = 50, key = if (ask.tmdbId > 0) "tmdb:${ask.tmdbId}" else "ext:${ask.extId}", kind = ask.kind, tmdbId = ask.tmdbId, extId = ask.extId, title = ask.title)
    }

    override suspend fun mine(): List<KidRequest> {
        calls += "mine"
        mineFailure?.let { throw it }
        return mineList
    }

    override suspend fun all(): List<KidRequest> {
        calls += "all"
        return allList
    }

    override suspend fun pending(): Int {
        calls += "pending"
        pendingFailure?.let { throw it }
        return pendingCount
    }

    override suspend fun approve(id: Long): KidRequest {
        calls += "approve $id"
        answerFailure?.let { throw it }
        allList = allList.map { if (it.id == id) it.copy(status = RequestStatus.APPROVED) else it }
        return allList.first { it.id == id }
    }

    override suspend fun decline(id: Long, reason: String): KidRequest {
        calls += "decline $id $reason"
        answerFailure?.let { throw it }
        allList = allList.map { if (it.id == id) it.copy(status = RequestStatus.DECLINED, reason = reason) else it }
        return allList.first { it.id == id }
    }

    companion object {
        fun conflict() = ApiException(409, "Already answered")
    }
}
