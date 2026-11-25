package app.antresol.ui.ways

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.ways.AddonSection
import app.antresol.core.ways.WaysApi
import app.antresol.core.ways.WaysSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The title searches of the server's addons for a search page, the part both apps share: what each
 * addon found (a section apiece, as it answers) and whether any addon is still looking. Nothing here
 * ever fails the page: an addon that does not answer simply adds nothing.
 */
class AddonTitleSearch(
    private val api: WaysApi,
    /** The addons' ways of the session; null: there are none, so nothing is searched. */
    private val source: WaysSource?,
    private val scope: CoroutineScope,
) {
    /** What the addons found so far, in the addons' order. */
    var sections: List<AddonSection> by mutableStateOf(emptyList())
        private set

    /** True from the moment a search is asked for until every addon has answered (or failed). */
    var searching: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** Counts the searches asked for: the one that ends is the newest, or it leaves [searching] to the newer. */
    private var round = 0

    /** Drops the last search and its answers. */
    fun clear() {
        round++
        job?.cancel()
        job = null
        sections = emptyList()
        searching = false
    }

    /** Searches [query] after [debounceMs] (typing again within it restarts); the old answers stay until the new search starts. */
    fun start(query: String, debounceMs: Long) {
        job?.cancel()
        val source = source ?: return
        val mine = ++round
        searching = true
        job = scope.launch {
            try {
                delay(debounceMs)
                sections = emptyList()
                source.get().search(api, query).collect { sections = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // an addon that cannot be reached adds nothing
            } finally {
                if (mine == round) searching = false
            }
        }
    }
}
