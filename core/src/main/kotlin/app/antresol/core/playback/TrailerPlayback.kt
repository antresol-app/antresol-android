package app.antresol.core.playback

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.TrailerPlay
import kotlinx.coroutines.delay

/** The server stayed busy fetching other trailers for longer than [TrailerPlayback.BUSY_BUDGET_MS]. */
class TrailerBusyException(cause: ApiException) : Exception(cause.message, cause)

/** The server does not know this trailer (404): it was never listed, or the list is out of date. */
class TrailerUnavailableException(cause: ApiException) : Exception(cause.message, cause)

/** Asking the server for a trailer it plays itself (`POST /api/trailers/{key}/play`). */
object TrailerPlayback {
    /** How long a busy server (503) is waited for, in its `Retry-After` steps. */
    const val BUSY_BUDGET_MS = 30_000L

    /** Wait between busy answers without `Retry-After`. */
    private const val DEFAULT_RETRY_MS = 2_000L
    private const val MIN_RETRY_MS = 500L
    private const val MAX_RETRY_MS = 10_000L

    /**
     * Where to play the trailer behind [play]. A 503 is asked again after its `Retry-After` until
     * about [budgetMs] of waiting; then [TrailerBusyException]. A 404 is [TrailerUnavailableException];
     * any other failure (502: the fetch failed, with the server's translated message) is thrown as is.
     */
    suspend fun open(
        client: AntresolClient,
        play: String,
        budgetMs: Long = BUSY_BUDGET_MS,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): TrailerPlay {
        var waited = 0L
        while (true) {
            try {
                return client.trailerPlay(play)
            } catch (e: ApiException) {
                when (e.status) {
                    503 -> {
                        val step = (e.retryAfterSec?.times(1_000) ?: DEFAULT_RETRY_MS).coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
                        if (waited >= budgetMs) throw TrailerBusyException(e)
                        val next = step.coerceAtMost(budgetMs - waited)
                        wait(next)
                        waited += next
                    }
                    404 -> throw TrailerUnavailableException(e)
                    else -> throw e
                }
            }
        }
    }
}
