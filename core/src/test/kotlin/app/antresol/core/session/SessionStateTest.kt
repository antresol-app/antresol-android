package app.antresol.core.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    private val admin = StoredSession("http://nas:8420", "srv", 1, "t1", "Admin", "grad-1")
    private val kid = StoredSession("http://nas:8420", "srv", 2, "t2", "Kiddo", "grad-4")

    @Test
    fun signInSwitchAndForget() {
        var state = SessionState().signedIn(admin).signedIn(kid)
        assertEquals(kid, state.active)
        assertEquals(2, state.saved.size)
        assertEquals(admin, state.tokenFor("srv", 1))

        state = state.signedIn(admin.copy(token = "t1b"))
        assertEquals("t1b", state.tokenFor("srv", 1)?.token)
        assertEquals(2, state.saved.size)

        state = state.switchedOut()
        assertNull(state.active)
        assertEquals(2, state.saved.size)

        state = state.signedIn(kid).forgotten("srv", 2)
        assertNull(state.active)
        assertNull(state.tokenFor("srv", 2))
        assertEquals("http://nas:8420", state.serverUrl)
    }

    @Test
    fun deviceIds() {
        assertTrue(DeviceIds.isValid(DeviceIds.generate()))
        assertFalse(DeviceIds.isValid("short"))
        assertFalse(DeviceIds.isValid("has space in it"))
        assertEquals("abcd-1234", DeviceIds.validOrNew("abcd-1234"))
        assertTrue(DeviceIds.isValid(DeviceIds.validOrNew("bad id")))
    }

    @Test
    fun inMemoryStoreUpdatesAtomically() = runTest {
        val store = InMemorySessionStore(deviceId = "device-0001")
        assertEquals("device-0001", store.deviceId())
        store.update { it.signedIn(admin) }
        assertEquals(admin, store.load().active)
    }
}
