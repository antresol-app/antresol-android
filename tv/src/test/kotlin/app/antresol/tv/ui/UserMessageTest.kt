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
    private val resources get() = RuntimeEnvironment.getApplication().resources

    private fun throttled(message: String, retryAfterSec: Long? = null) = ApiException(429, message, retryAfterSec = retryAfterSec).userMessage(resources)

    @Test
    fun aThrottledRequestSaysHowLongToWait() {
        // No sentence from the server (the client's "HTTP 429"): ours, with the wait time when it is known. One instruction, not two.
        assertEquals("Too many attempts. Try again in 30 s.", throttled("HTTP 429", 30))
        assertEquals("Too many attempts. Try again later.", throttled("HTTP 429"))
        // The server's sentence, with the wait time.
        assertEquals("Slow down. Try again in 5 s.", throttled("Slow down.", 5))
        assertEquals("Slow down.", throttled("Slow down."))
    }

    @Test
    fun aWaitOfNoTimeIsNotShownAsSeconds() {
        assertEquals("Too many attempts. Try again later.", throttled("HTTP 429", 0))
        assertEquals("Too many attempts. Try again later.", throttled("HTTP 429", -1))
        assertEquals("Slow down.", throttled("Slow down.", 0))
    }

    @Test
    @Config(qualifiers = "ru")
    fun theSameInRussian() {
        assertEquals("Слишком много попыток. Повторите через 30 с.", throttled("HTTP 429", 30))
        assertEquals("Слишком много попыток. Попробуйте позже.", throttled("HTTP 429"))
        assertEquals("Слишком много попыток. Попробуйте позже.", throttled("HTTP 429", 0))
        assertEquals("Не так быстро. Повторите через 5 с.", throttled("Не так быстро.", 5))
    }
}
