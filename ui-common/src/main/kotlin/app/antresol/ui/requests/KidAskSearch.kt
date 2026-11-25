package app.antresol.ui.requests

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.requests.RequestsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A kid's search for titles to ask a parent for (`/api/requests/search`), the part both apps share. One call per
 * kind (video, music, books…), made when a page's filter first shows that kind; what each found stays until
 * the query changes. A kind that fails adds nothing and sets [failed]; it never fails the page.
 */
class KidAskSearch(
    private val api: RequestsApi,
    private val scope: CoroutineScope,
) {
    private var query = ""

    /** What each asked kind found for [query]. */
    private var found: Map<String, List<KidCandidate>> by mutableStateOf(emptyMap())

    /** The kinds whose answer is still on its way. */
    private var waiting: Set<String> by mutableStateOf(emptySet())

    /** A kind did not answer (requests closed, TMDB down): the page says so under an empty result. */
    var failed: Boolean by mutableStateOf(false)
        private set

    private val jobs = HashMap<String, Job>()

    /** Counts the queries: an answer of an older one is dropped. */
    private var round = 0

    /** Some asked kind has not answered yet: an empty page is not «nothing found» yet. */
    val searching: Boolean get() = waiting.isNotEmpty()

    /** What the [kinds] found, in their order, each title once. */
    fun candidates(kinds: List<String>): List<KidCandidate> =
        kinds.flatMap { found[it].orEmpty() }.distinctBy { it.kind to (it.kidRequest?.key ?: "${it.tmdbId}|${it.extId}|${it.title}") }

    /** Drops the last search and its answers. */
    fun clear() {
        round++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        query = ""
        found = emptyMap()
        waiting = emptySet()
        failed = false
    }

    /** Searches [text] for [kinds] after [debounceMs] (typing again within it restarts). */
    fun start(text: String, kinds: List<String>, debounceMs: Long) {
        clear()
        query = text
        want(kinds, debounceMs)
    }

    /** The page shows more [kinds] for the same query: those not asked yet are. */
    fun want(kinds: List<String>, debounceMs: Long = 0) {
        if (query.isEmpty()) return
        for (kind in kinds) {
            if (kind in found || jobs[kind]?.isActive == true) continue
            fetch(kind, query, round, debounceMs)
        }
    }

    /** Asks again for what was asked, keeping what is shown meanwhile (the kid's request may have been answered, or made on a title page). */
    fun refresh() {
        if (query.isEmpty() || searching) return
        val asked = found.keys.toList()
        val mine = round
        for (kind in asked) {
            scope.launch {
                val list = try {
                    api.search(query, kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                if (mine == round) found = found + (kind to list)
            }
        }
    }

    /** The kid asked for a title: its results show the request from now on. */
    fun put(request: KidRequest) {
        found = found.mapValues { (_, list) ->
            list.map { c ->
                val state = c.kidRequest
                if (state != null && c.slot == request.slot) c.copy(kidRequest = state.copy(request = request)) else c
            }
        }
    }

    private fun fetch(kind: String, text: String, mine: Int, debounceMs: Long) {
        waiting = waiting + kind
        jobs[kind] = scope.launch {
            try {
                delay(debounceMs)
                val list = api.search(text, kind)
                if (mine == round) found = found + (kind to list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == round) {
                    failed = true
                    found = found + (kind to emptyList())
                }
            } finally {
                if (mine == round) waiting = waiting - kind
            }
        }
    }
}
