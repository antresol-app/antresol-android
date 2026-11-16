package app.antresol.core.reading

import app.antresol.core.playback.PendingWrites
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PageProgressSaverTest {
    private val written = mutableListOf<String>()
    private var failing = false
    private val pendingWrites = PendingWrites()

    private fun TestScope.saver() = PageProgressSaver(backgroundScope, backgroundScope, pendingWrites, delayMs = 1_000) { update ->
        if (failing) throw IOException("offline")
        written += update.locator.orEmpty()
    }

    @Test
    fun thePageIsSavedAMomentAfterItSettles() = runTest {
        val saver = saver()
        saver.onPage(3, 10)
        advanceTimeBy(500)
        saver.onPage(4, 10)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(emptyList<String>(), written)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("page:5"), written)
    }

    @Test
    fun closingSavesThePageTurnedJustBeforeAndTracksTheWrite() = runTest {
        val saver = saver()
        saver.onPage(3, 10)
        advanceTimeBy(1_000)
        runCurrent()
        saver.onPage(4, 10)
        val before = pendingWrites.completions.value
        saver.close()
        runCurrent()
        assertEquals(listOf("page:4", "page:5"), written)
        assertEquals(before + 1, pendingWrites.completions.value)
    }

    @Test
    fun aPageTheServerAlreadyHasIsNotWrittenAgain() = runTest {
        val saver = saver()
        saver.opened(6, 10, onServer = true)
        saver.close()
        runCurrent()
        assertEquals(emptyList<String>(), written)
        assertEquals(0L, pendingWrites.completions.value)
    }

    @Test
    fun aFirstOpeningIsWrittenOnClose() = runTest {
        val saver = saver()
        saver.opened(0, 10, onServer = false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList<String>(), written)
        saver.close()
        runCurrent()
        assertEquals(listOf("page:1"), written)
    }

    @Test
    fun aFailedSaveIsRetriedOnCloseAndWhatFollowsStillRuns() = runTest {
        val saver = saver()
        failing = true
        saver.onPage(2, 10)
        advanceTimeBy(1_000)
        runCurrent()
        failing = false
        var closed = false
        saver.close { closed = true }
        runCurrent()
        assertEquals(listOf("page:3"), written)
        assertEquals(true, closed)
    }
}
