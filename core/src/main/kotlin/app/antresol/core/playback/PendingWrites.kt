package app.antresol.core.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Final progress writes still in flight after their player closed. Screens that show progress
 * (Home's Continue Watching, a detail page) wait for them before refreshing, so they never show the
 * position from before the last write.
 */
class PendingWrites {
    private val jobs = LinkedHashSet<Job>()
    private val finished = MutableStateFlow(0L)

    /**
     * Counts the writes that have finished. A screen that is already showing (its player closed in
     * picture-in-picture, a reader popped off the back stack) refreshes when this changes.
     */
    val completions: StateFlow<Long> = finished.asStateFlow()

    fun track(job: Job) {
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion {
            synchronized(jobs) { jobs -= job }
            finished.update { it + 1 }
        }
    }

    /** Waits for the writes in flight, at most [timeoutMs]. */
    suspend fun await(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val running = synchronized(jobs) { jobs.toList() }
        if (running.isEmpty()) return
        withTimeoutOrNull(timeoutMs) { running.joinAll() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
