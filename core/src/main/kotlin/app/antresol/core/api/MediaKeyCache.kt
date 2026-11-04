package app.antresol.core.api

import app.antresol.core.model.MediaKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * Keeps one media key so players can get a usable `?mk=` without a request per URL.
 *
 * A key belongs to the session (token) that fetched it (spec 2.5), so it is refetched whenever
 * [token] changes: another profile never streams with the previous profile's key, and a re-sign-in
 * (which revokes the old session and its keys) never reuses a dead one. It is also refreshed when
 * less than the asked lifetime is left, so a long film does not outlive its key.
 */
class MediaKeyCache(
    private val fetch: suspend () -> MediaKey,
    private val token: () -> String? = { null },
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private var cached: MediaKey? = null
    private var cachedFor: String? = null

    /** A key valid for at least [minRemaining] more (default one hour) for the current token. */
    suspend fun get(minRemaining: Duration = DEFAULT_MIN_REMAINING): String = mutex.withLock {
        val current = cached
        val owner = token()
        if (current != null && cachedFor == owner && remaining(current) > minRemaining) return current.key
        fetch().also {
            cached = it
            cachedFor = owner
        }.key
    }

    /** Drops the cached key, e.g. after sign-out or when a player gets 401. */
    suspend fun invalidate() = mutex.withLock {
        cached = null
        cachedFor = null
    }

    private fun remaining(key: MediaKey): Duration {
        val expiry = runCatching { Instant.parse(key.expiresAt) }.getOrNull() ?: return Duration.ZERO
        return Duration.between(now(), expiry)
    }

    companion object {
        val DEFAULT_MIN_REMAINING: Duration = Duration.ofHours(1)
    }
}
