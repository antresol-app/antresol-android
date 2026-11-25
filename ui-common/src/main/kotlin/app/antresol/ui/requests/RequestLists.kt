package app.antresol.ui.requests

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.core.requests.RequestsApi
import app.antresol.ui.LoadState
import app.antresol.ui.load as loadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a kid asked for, newest first (a title page of its own for each video). */
class MyRequests(
    private val api: RequestsApi,
    private val scope: CoroutineScope,
) {
    var state: LoadState<List<KidRequest>> by mutableStateOf(LoadState.Loading)
        private set

    private var job: Job? = null

    /** Reads the list; [quiet] keeps what is shown while it loads (a screen that came back). */
    fun load(quiet: Boolean = false) {
        // A screen that came back asks again quietly: not while the read it started with is still on its way.
        if (quiet && job?.isActive == true) return
        job?.cancel()
        if (!quiet || state !is LoadState.Ready) state = LoadState.Loading
        job = scope.launch {
            val fresh = loadState { api.mine().sortedForKid() }
            // A failed quiet read leaves the list that is shown.
            if (!(quiet && fresh is LoadState.Failed && state is LoadState.Ready)) state = fresh
        }
    }
}

/**
 * The requests an administrator answers: the waiting ones first, then those answered in the last 30 days.
 * «Approve» confirms without a download (a download of the title through its ways approves it too); «Decline»
 * takes an optional reason the kid sees.
 */
class RequestsToAnswer(
    private val api: RequestsApi,
    private val scope: CoroutineScope,
) {
    var state: LoadState<List<KidRequest>> by mutableStateOf(LoadState.Loading)
        private set

    /** The request being answered now, and the failure of the last answer. */
    var busy: Long? by mutableStateOf(null)
        private set
    var answerFailed: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null

    val waiting: List<KidRequest> get() = (state as? LoadState.Ready)?.value.orEmpty().filter { it.status == RequestStatus.PENDING }
    val answered: List<KidRequest> get() = (state as? LoadState.Ready)?.value.orEmpty().filter { it.status != RequestStatus.PENDING }

    fun load(quiet: Boolean = false) {
        // A screen that came back asks again quietly: not while the read it started with is still on its way.
        if (quiet && job?.isActive == true) return
        job?.cancel()
        if (!quiet || state !is LoadState.Ready) state = LoadState.Loading
        job = scope.launch {
            val fresh = loadState { api.all() }
            if (!(quiet && fresh is LoadState.Failed && state is LoadState.Ready)) state = fresh
        }
    }

    fun approve(request: KidRequest) = answer(request) { api.approve(request.id) }

    fun decline(request: KidRequest, reason: String) = answer(request) { api.decline(request.id, reason.trim()) }

    private fun answer(request: KidRequest, call: suspend () -> KidRequest) {
        if (busy != null) return
        busy = request.id
        answerFailed = false
        scope.launch {
            try {
                call()
                load(quiet = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                answerFailed = true
            } finally {
                busy = null
            }
        }
    }
}

/** How many requests wait for an administrator's answer (the badge of the «Requests» entry). */
class PendingRequests(
    private val api: RequestsApi,
    private val scope: CoroutineScope,
) {
    var count: Int by mutableStateOf(0)
        private set

    fun refresh() {
        scope.launch {
            try {
                count = api.pending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a hint only: the last number stays
            }
        }
    }
}

/** The server lists a kid's requests newest first already; this keeps that even for a list read from a cache. */
internal fun List<KidRequest>.sortedForKid(): List<KidRequest> = sortedWith(compareByDescending<KidRequest> { it.createdAt }.thenByDescending { it.id })
