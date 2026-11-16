package app.antresol.core.api

import kotlinx.coroutines.CancellationException

/**
 * [block]'s result, or null when it failed. For calls whose failure is not worth showing. Unlike
 * `runCatching`, a cancellation is rethrown: the coroutine around a cancelled call stops there.
 */
suspend inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
