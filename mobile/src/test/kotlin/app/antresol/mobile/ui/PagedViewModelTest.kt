package app.antresol.mobile.ui

import app.antresol.core.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PagedViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** 10 numbers in pages of 3; offsets in [failing] throw once per entry. */
    private class Numbers(val failing: MutableList<Int> = mutableListOf()) : PagedViewModel<Int>() {
        val all = (0 until 10).toList()

        override suspend fun fetch(offset: Int): Page<Int> {
            if (failing.remove(offset)) throw IOException("offline")
            return Page(all.drop(offset).take(3), all.size)
        }

        override fun keyOf(item: Int): Any = item

        suspend fun follow(from: Int, wanted: () -> Boolean = { true }, onItems: (List<Int>) -> Unit) =
            followToEnd(from, wanted, onItems)
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun Numbers.loaded(): Numbers = apply {
        reload()
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun followDeliversEveryRemainingPage() = runTest(dispatcher) {
        val list = Numbers().loaded()
        val delivered = ArrayList<Int>()
        list.follow(3) { delivered += it }
        assertEquals((3 until 10).toList(), delivered)
        assertEquals(10, (list.state as LoadState.Ready).value.items.size)
    }

    @Test
    fun followRetriesAFailedPageAutomatically() = runTest(dispatcher) {
        val list = Numbers(failing = mutableListOf(6, 6)).loaded()
        val delivered = ArrayList<Int>()
        list.follow(3) { delivered += it }
        assertEquals((3 until 10).toList(), delivered)
        assertNull(list.pageError)
    }

    @Test
    fun followWaitsForTryAgainAfterItsRetriesAndKeepsGoing() = runTest(dispatcher) {
        val list = Numbers(failing = mutableListOf(3, 3, 3, 3)).loaded()
        val delivered = ArrayList<Int>()
        val job = launch { list.follow(3) { delivered += it } }
        advanceUntilIdle()
        // Every automatic retry failed: the queue waits, the list offers "Try Again".
        assertNotNull(list.pageError)
        assertEquals(emptyList<Int>(), delivered)
        list.loadMore(retry = true)
        advanceUntilIdle()
        assertEquals((3 until 10).toList(), delivered)
        assertEquals(true, job.isCompleted)
    }

    @Test
    fun followIncludesPagesTheListLoadedMeanwhile() = runTest(dispatcher) {
        val list = Numbers().loaded()
        list.loadMore()
        runCurrent()
        val delivered = ArrayList<Int>()
        // Started from the first page only: the page loaded by scrolling is delivered too.
        list.follow(3) { delivered += it }
        assertEquals((3 until 10).toList(), delivered)
    }

    @Test
    fun followStopsWhenNoLongerWanted() = runTest(dispatcher) {
        val list = Numbers().loaded()
        var wanted = true
        val delivered = ArrayList<Int>()
        list.follow(3, wanted = { wanted }) {
            delivered += it
            wanted = false
        }
        assertEquals(listOf(3, 4, 5), delivered)
    }
}
