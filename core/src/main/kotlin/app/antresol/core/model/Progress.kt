package app.antresol.core.model

import kotlinx.serialization.Serializable

/** `GET /api/progress/{itemId}`. [updatedAt] is null when nothing was saved yet. */
@Serializable
data class Progress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val finished: Boolean = false,
    val locator: String = "",
    val fraction: Double = 0.0,
    val updatedAt: String? = null,
) {
    val hasProgress: Boolean get() = updatedAt != null
}

/**
 * Body of `PUT /api/progress/{itemId}`. Use [playback] for video/audio, [reader] for books and comics.
 * [updatedAt] (RFC 3339) marks when an offline position was recorded; older writes are ignored by the server.
 */
@Serializable
data class ProgressUpdate(
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val locator: String? = null,
    val fraction: Double? = null,
    val updatedAt: String? = null,
) {
    companion object {
        fun playback(positionMs: Long, durationMs: Long) =
            ProgressUpdate(positionMs = positionMs, durationMs = durationMs)

        fun reader(locator: String, fraction: Double) =
            ProgressUpdate(locator = locator, fraction = fraction)
    }
}

@Serializable
internal data class WatchedRequest(val watched: Boolean)
