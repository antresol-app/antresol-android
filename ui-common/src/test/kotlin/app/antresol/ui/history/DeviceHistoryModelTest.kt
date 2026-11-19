package app.antresol.ui.history

import app.antresol.core.api.ApiException
import app.antresol.core.model.DeviceHistoryRequest
import app.antresol.core.model.DeviceHistoryResult
import app.antresol.core.model.HistoryRef
import app.antresol.core.model.ManagedAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceHistoryModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val accounts = mutableListOf(ManagedAccount(20, "Living Room"))
    private val switches = mutableListOf<DeviceHistoryRequest>()
    private val heard = mutableListOf<HistoryRef?>()
    private var refuse: ApiException? = null
    private val api = object : DeviceHistoryApi {
        override suspend fun accounts() = accounts.toList()

        override suspend fun create(name: String): ManagedAccount {
            refuse?.let { throw it }
            return ManagedAccount(accounts.size + 20L, name).also { accounts += it }
        }

        override suspend fun switch(request: DeviceHistoryRequest): DeviceHistoryResult {
            refuse?.let { throw it }
            switches += request
            val on = accounts.firstOrNull { it.id == request.accountId }.takeIf { request.separate }
            if (!request.separate && request.merge) accounts.removeAt(0)
            return DeviceHistoryResult(1, on?.let { HistoryRef(it.id, it.name) })
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = DeviceHistoryModel(api) { heard += it }

    @Test
    fun switchesOnToAnAccountAndOffWithTheMergeChoice() = runTest(dispatcher) {
        val model = model()
        model.load()
        runCurrent()
        assertEquals(listOf("Living Room"), model.accounts?.map { it.name })
        model.use(20)
        runCurrent()
        assertEquals(DeviceHistoryRequest(separate = true, accountId = 20), switches.last())
        assertEquals(HistoryRef(20, "Living Room"), heard.last())
        model.turnOff(merge = true)
        runCurrent()
        assertEquals(DeviceHistoryRequest(separate = false, merge = true), switches.last())
        assertNull(heard.last())
        // the merged history is gone from the list
        assertEquals(emptyList<ManagedAccount>(), model.accounts)
        assertFalse(model.busy)
    }

    @Test
    fun createsByNameThenSwitchesIntoIt() = runTest(dispatcher) {
        val model = model()
        model.create("   ")
        runCurrent()
        assertTrue("a blank name is not sent", switches.isEmpty())
        model.create("  Kitchen ")
        runCurrent()
        assertEquals(DeviceHistoryRequest(separate = true, accountId = 21), switches.single())
        assertEquals(HistoryRef(21, "Kitchen"), heard.single())
        assertEquals(listOf("Living Room", "Kitchen"), model.accounts?.map { it.name })
    }

    @Test
    fun aRefusalIsKeptAndTheSessionNotTold() = runTest(dispatcher) {
        val model = model()
        refuse = ApiException(403, "Only an administrator can change this")
        model.use(20)
        runCurrent()
        assertEquals(403, (model.error as ApiException).status)
        assertTrue(heard.isEmpty())
        refuse = null
        model.use(20)
        runCurrent()
        assertNull(model.error)
        assertEquals(1, heard.size)
    }
}
