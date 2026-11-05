package app.antresol.mobile.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Audiobook progress writes sent after a book stopped being current. Sign-out waits for them, so
 * the last position reaches the server before the token is revoked. Main-thread only.
 */
internal object FinalProgressWrites {
    private val jobs = LinkedHashSet<Job>()

    fun track(job: Job) {
        jobs += job
        job.invokeOnCompletion { jobs -= job }
    }

    /** Waits for the writes in flight, at most [timeoutMs]. */
    suspend fun await(timeoutMs: Long) {
        val running = jobs.toList()
        if (running.isEmpty()) return
        withTimeoutOrNull(timeoutMs) { running.joinAll() }
    }
}
