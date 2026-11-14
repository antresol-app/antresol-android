package app.antresol.core.pairing

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Me
import app.antresol.core.model.PairPoll
import app.antresol.core.model.PairStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** The two TV calls of QR sign-in, so the flow can be tested without a server. */
interface PairingApi {
    suspend fun start(device: DeviceInfo): PairStart

    suspend fun poll(secret: String): PairPoll
}

fun AntresolClient.pairingApi(): PairingApi = object : PairingApi {
    override suspend fun start(device: DeviceInfo): PairStart = pairStart(device)

    override suspend fun poll(secret: String): PairPoll = pairPoll(secret)
}

/** A `start` answer the TV got before its QR screen opened, and when ([PairingFlow]'s clock). */
data class StartedPairing(val start: PairStart, val receivedAtMs: Long)

/**
 * Hands a [StartedPairing] from the TV setup listener to the pairing QR screen of the same
 * server. [take] returns it once.
 */
class PairingTicket {
    private var pending: StartedPairing? = null

    @Synchronized
    fun offer(started: StartedPairing) {
        pending = started
    }

    /** The handed-over code for [serverId] (or none), and forgets it. */
    @Synchronized
    fun take(serverId: String): StartedPairing? {
        val started = pending ?: return null
        pending = null
        return started.takeIf { it.start.serverId.isBlank() || it.start.serverId.equals(serverId, ignoreCase = true) }
    }
}

/** What the TV's QR screen shows. Times are on [PairingFlow]'s clock (milliseconds). */
sealed interface PairingState {
    /** Asking the server for a code. */
    data object Starting : PairingState

    /** Show [url] as the QR and [code] (`ABC 234`) until [deadlineMs]. */
    data class Pending(val code: String, val url: String, val serverId: String, val deadlineMs: Long) : PairingState {
        fun secondsLeft(nowMs: Long): Long = ((deadlineMs - nowMs + 999) / 1000).coerceAtLeast(0)
    }

    /** Done: store [token] as a normal sign-in of [user]. The flow ends. */
    data class Approved(val token: String, val user: Me) : PairingState

    /** The phone declined. The flow ends; the screen offers a new code (collect again). */
    data class Denied(val message: String?) : PairingState

    /** `start` failed ([error]: network or server); trying again at [retryAtMs]. */
    data class Retrying(val error: Throwable, val retryAtMs: Long) : PairingState
}

/**
 * The TV side of QR sign-in (contract §2.2–2.3): `start` → show the code → `poll` every
 * `interval` s → approved / denied; an expired code (the countdown ran out, or poll says
 * `expired`) is replaced by a new `start` automatically. Network errors while polling keep
 * polling; a failing `start` is retried after [retryMs] (or the server's `Retry-After`).
 *
 * The countdown uses the device clock: the server's `expiresAt` is trusted only when it is
 * within [maxTtlMs] of now (TV clocks are often wrong), otherwise the contract's 5 minutes.
 * At the deadline one more poll runs before the new code, so an approval that came in the last
 * seconds is still picked up (the server keeps it for 60 s).
 *
 * Collect it while the screen is visible; cancelling stops polling (the code simply expires).
 * There is one poller per collection, as the contract requires.
 */
class PairingFlow(
    private val api: PairingApi,
    private val device: suspend () -> DeviceInfo,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryMs: Long = 5_000,
    private val maxTtlMs: Long = DEFAULT_TTL_MS + 60_000,
    initial: StartedPairing? = null,
) {
    /**
     * A code the TV already started (setup by phone): the first collection shows and polls it
     * without a new `start` (that would cancel the code the phone is approving right now).
     */
    private val handedOver = java.util.concurrent.atomic.AtomicReference(initial)

    fun states(): Flow<PairingState> = flow {
        while (true) {
            val handedOver = handedOver.getAndSet(null)
            if (handedOver == null) emit(PairingState.Starting)
            val started = handedOver?.start ?: startOrNull() ?: continue
            val receivedAt = handedOver?.receivedAtMs ?: now()
            val deadline = receivedAt + ttlMs(started.expiresAt, receivedAt)
            emit(PairingState.Pending(normalizedCode(started.code), started.url, started.serverId, deadline))
            when (val outcome = pollUntilDone(started, deadline)) {
                is PairingState.Approved, is PairingState.Denied -> {
                    emit(outcome)
                    return@flow
                }
                else -> Unit // expired: a new code
            }
        }
    }

    /** One `start`; on failure emits [PairingState.Retrying], waits and returns null. */
    private suspend fun FlowCollector<PairingState>.startOrNull(): PairStart? {
        val error: Exception
        var waitMs = retryMs
        try {
            return api.start(device())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            error = e
            if (e.retryAfterSec != null) waitMs = e.retryAfterSec * 1000
        } catch (e: IOException) {
            error = e
        }
        emit(PairingState.Retrying(error, now() + waitMs))
        delay(waitMs)
        return null
    }

    /** Polls until approved/denied (returned) or the code is gone (returns null). */
    private suspend fun pollUntilDone(started: PairStart, deadline: Long): PairingState? {
        var intervalMs = started.interval.coerceIn(1, 30) * 1000L
        while (true) {
            delay(intervalMs)
            val answer = try {
                api.poll(started.secret)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                null // network error (or a server error): keep polling
            }
            when (answer) {
                is PairPoll.Approved -> return PairingState.Approved(answer.token, answer.user)
                is PairPoll.Denied -> return PairingState.Denied(answer.message)
                is PairPoll.Expired -> return null
                is PairPoll.Throttled -> delay(answer.retryAfterSec.coerceIn(1, 900) * 1000)
                is PairPoll.Pending -> answer.intervalSec?.let { intervalMs = it.coerceIn(1, 30) * 1000L }
                null -> Unit
            }
            if (now() >= deadline) return null
        }
    }

    private fun ttlMs(expiresAt: String, nowMs: Long): Long {
        val expires = try {
            Instant.parse(expiresAt).toEpochMilli()
        } catch (_: DateTimeParseException) {
            return DEFAULT_TTL_MS
        }
        val ttl = expires - nowMs
        return if (ttl in 10_000..maxTtlMs) ttl else DEFAULT_TTL_MS
    }

    companion object {
        /** The contract's code lifetime. */
        const val DEFAULT_TTL_MS = 5 * 60_000L
    }
}
