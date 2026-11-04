package app.antresol.core.api

import app.antresol.core.model.MediaKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * Keeps one media key and refreshes it when less than [minRemaining] of its 12 h lifetime is left,
 * so players can always get a usable `?mk=` without a request per URL.
 */
class MediaKeyCache(
    private val fetch: suspend () -> MediaKey,
    private val now: () -> Instant = Instant::now,
    private val minRemaining: Duration = Duration.ofHours(1),
) {
    private val mutex = Mutex()
    private var cached: MediaKey? = null

    suspend fun get(): String = mutex.withLock {
        val current = cached
        if (current != null && isFresh(current)) return current.key
        fetch().also { cached = it }.key
    }

    /** Drops the cached key, e.g. after sign-out or when a player gets 401. */
    suspend fun invalidate() = mutex.withLock { cached = null }

    private fun isFresh(key: MediaKey): Boolean {
        val expiry = runCatching { Instant.parse(key.expiresAt) }.getOrNull() ?: return false
        return Duration.between(now(), expiry) > minRemaining
    }
}
