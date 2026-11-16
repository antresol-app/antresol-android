package app.antresol.core.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AttemptTest {
    @Test
    fun aResultIsReturnedAndAFailureIsNull() = runTest {
        assertEquals(7, attempt { 7 })
        assertNull(attempt<Int> { throw IOException("offline") })
    }

    @Test
    fun aCancellationIsRethrownSoNothingRunsAfterIt() = runTest {
        var continued = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            attempt<Unit> { awaitCancellation() }
            continued = true
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(continued)
    }
}
