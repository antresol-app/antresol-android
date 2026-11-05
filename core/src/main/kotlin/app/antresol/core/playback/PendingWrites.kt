package app.antresol.core.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Final progress writes still in flight after their player closed. Screens that show progress
 * (Home's Continue Watching, a detail page) wait for them before refreshing, so they never show the
 * position from before the last write.
 */
class PendingWrites {
    private val jobs = LinkedHashSet<Job>()

    fun track(job: Job) {
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
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
