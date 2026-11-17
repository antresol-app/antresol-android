package app.antresol.tv.ui

import app.antresol.core.api.ApiException
import app.antresol.ui.userMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What a throttled request (429) reads as, with and without the server's own sentence and wait time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class UserMessageTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun aThrottledRequestSaysHowLongToWait() {
        // No sentence from the server (the client's "HTTP 429"): ours, with the wait time when it is known.
        assertEquals("Too many attempts. Try again later. Try again in 30 s.", ApiException(429, "HTTP 429", retryAfterSec = 30).userMessage(resources))
        assertEquals("Too many attempts. Try again later.", ApiException(429, "HTTP 429").userMessage(resources))
        // The server's sentence, with the wait time.
        assertEquals("Slow down. Try again in 5 s.", ApiException(429, "Slow down.", retryAfterSec = 5).userMessage(resources))
        assertEquals("Slow down.", ApiException(429, "Slow down.").userMessage(resources))
    }
}
